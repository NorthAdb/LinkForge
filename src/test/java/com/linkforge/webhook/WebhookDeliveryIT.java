package com.linkforge.webhook;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import com.linkforge.webhook.support.WebhookSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-wave2 B5: click webhooks on the A7 outbox chain. Chain-dependent tests await
 * the DELIVERY ROW first (stage 1 — the consume-hook evidence, status-agnostic
 * convergence like every shared-DB IT here) and only then the stub hop (stage 2 —
 * relay HTTP evidence); a failure message always carries the row so a red run
 * says which hop broke.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-hook:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "linkforge.webhook.allow-private-targets=true",
        "linkforge.webhook.relay-enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class WebhookDeliveryIT extends AbstractIntegrationTest {

    private static HttpServer stub;
    /** Recorded requests in arrival order: [path, eventIdHeader, timestampHeader, signatureHeader, body]. */
    private static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger PORT = new AtomicInteger();
    /** Per-path status scripts: each test owns its own stub path, so concurrent
     * deliveries (one row per test tenant, drained by the same relay) never
     * consume each other's scripted statuses. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Queue<Integer>> SCRIPTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClickStatsService clickStatsService;

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        PORT.set(stub.getAddress().getPort());
        stub.createContext("/hook", WebhookDeliveryIT::handle);
        stub.start();
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        RECEIVED.add(new String[]{
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_EVENT_ID),
                exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_TIMESTAMP),
                exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_SIGNATURE),
                new String(body, StandardCharsets.UTF_8)});
        java.util.Queue<Integer> script = SCRIPTS.get(exchange.getRequestURI().getPath());
        Integer scripted = script == null ? null : script.poll();
        int status = scripted != null ? scripted : 200;
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    private void configureEndpoint(String token, String path) throws Exception {
        SCRIPTS.put(path, new java.util.concurrent.ConcurrentLinkedQueue<>());
        var resp = rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + path, "enabled", true)),
                        jsonHeaders(token)), String.class);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    private static org.springframework.http.HttpHeaders jsonHeaders(String token) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** Tenant-scoped latest delivery row (each test owns one tenant and one click). */
    private Map<String, Object> latestDeliveryFor(String token) {
        Long tenantId = tenantIdOf(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempts, last_status_code, last_error, event_id "
                        + "FROM lf_webhook_delivery WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int deliveryCount(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_webhook_delivery WHERE event_id = ?", Integer.class, eventId);
    }

    private void awaitStubSaw(String eventId) {
        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                assertTrue(RECEIVED.stream().anyMatch(r -> eventId.equals(r[1])),
                        "stub must have received " + eventId + ", got " + RECEIVED.size() + " entries"));
    }

    @Test
    void signedDeliveryFlowsThroughTheRealOutboxChain() throws Exception {
        String token = registerForToken();
        configureEndpoint(token, "/hook-signed");
        String secret = body(rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(jsonHeaders(token)), String.class))
                .path("data").path("secretMasked").asText();
        assertTrue(secret.startsWith("v1:"), "GET only exposes the ciphertext mask");

        // at rest only ciphertext is stored
        Long tenantId = tenantIdOf(token);
        String stored = jdbc.queryForObject(
                "SELECT secret FROM lf_webhook_endpoint WHERE tenant_id = ?", String.class, tenantId);
        assertTrue(stored.startsWith("v1:"), "secret must be AES-GCM ciphertext: " + stored);

        String code = createLink(token, "https://example.com/hook-hit");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        // stage 1: the outbox chain must converge into a SENT delivery row
        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertEquals("SENT", row.get("status"), "delivery row: " + row);
        });
        String eventId = String.valueOf(latestDeliveryFor(token).get("event_id"));
        assertTrue(eventId.startsWith("evt_log-"), "event id derives from the click eventId");

        // stage 2: the relay hop must have reached the stub with verifiable headers
        awaitStubSaw(eventId);
        String[] request = RECEIVED.stream().filter(r -> eventId.equals(r[1])).findFirst().orElseThrow();

        long timestamp = Long.parseLong(request[2]);
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - timestamp) <= 300, "timestamp within ±300s");
        assertEquals(WebhookSigner.sign(secretPlainFor(tenantId), timestamp, request[4]), request[3],
                "signature must verify against the recorded timestamp + body");

        var payload = om.readTree(request[4]);
        assertEquals("link.clicked", payload.path("type").asText());
        assertEquals(code, payload.path("data").path("code").asText());
        assertFalse(payload.path("data").path("device").asText().isBlank());
        assertFalse(payload.path("data").path("refererHost").asText().isBlank());
        assertFalse(payload.has("ip") || payload.path("data").has("ip"), "raw IP is not pushed (privacy minimisation)");
        assertEquals(1, deliveryCount(eventId));
    }

    /** Decrypts the stored endpoint secret with the test master key via the service cipher. */
    @Autowired
    private com.linkforge.webhook.support.WebhookSecretCipher cipher;

    private String secretPlainFor(Long tenantId) {
        String stored = jdbc.queryForObject(
                "SELECT secret FROM lf_webhook_endpoint WHERE tenant_id = ?", String.class, tenantId);
        return cipher.decrypt(stored);
    }

    @Test
    void retryableFailuresBackOffThenSentWithStableEventId() throws Exception {
        String token = registerForToken();
        configureEndpoint(token, "/hook-retry");

        SCRIPTS.get("/hook-retry").addAll(List.of(500, 500)); // third attempt (default 200) succeeds
        String code = createLink(token, "https://example.com/hook-retry");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertEquals("SENT", row.get("status"), "third attempt must settle the delivery: " + row);
            assertTrue(((Number) row.get("attempts")).intValue() >= 3, "attempts must count every try: " + row);
        });
        String eventId = String.valueOf(latestDeliveryFor(token).get("event_id"));
        awaitStubSaw(eventId);
        long hitsForThisEvent = RECEIVED.stream().filter(r -> eventId.equals(r[1])).count();
        assertTrue(hitsForThisEvent >= 3, "three attempts for " + eventId + ", stub saw " + hitsForThisEvent
                + " of " + RECEIVED.size() + " total entries");
        assertEquals(1, deliveryCount(eventId), "still exactly one delivery row");
    }

    @Test
    void permanent4xxFailsImmediatelyWithoutRetry() throws Exception {
        String token = registerForToken();
        configureEndpoint(token, "/hook-400");

        SCRIPTS.get("/hook-400").add(400); // every attempt returns 400
        String code = createLink(token, "https://example.com/hook-400");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertEquals("FAILED", row.get("status"), "a 400 must fail the delivery on the first try: " + row);
            assertEquals(1, ((Number) row.get("attempts")).intValue(), "no retry after a permanent 4xx: " + row);
        });
        String eventId = String.valueOf(latestDeliveryFor(token).get("event_id"));
        awaitStubSaw(eventId);
        assertEquals(1, RECEIVED.size(), "exactly one stub hit for a permanent 4xx, got " + RECEIVED.size());
        assertEquals(1, deliveryCount(eventId));
    }

    @Test
    void maxAttemptsEndsInFailed() throws Exception {
        String token = registerForToken();
        configureEndpoint(token, "/hook-max");

        SCRIPTS.get("/hook-max").addAll(List.of(500, 500, 500, 500, 500, 500, 500, 500)); // one per attempt up to max-attempts
        String code = createLink(token, "https://example.com/hook-dead");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(90)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertEquals("FAILED", row.get("status"), "exhausting max-attempts must end in FAILED: " + row);
            assertEquals(8, ((Number) row.get("attempts")).intValue(),
                    "attempts must stop at linkforge.webhook.max-attempts: " + row);
        });
    }

    @Test
    void sameEventConsumedTwiceYieldsExactlyOneDelivery() throws Exception {
        String token = registerForToken();
        configureEndpoint(token, "/hook-idem");

        Long tenantId = tenantIdOf(token);
        String eventId = "evt-" + UUID.randomUUID();
        ClickEvent event = new ClickEvent(eventId, tenantId, 1L, "hookcase", 1L,
                "203.0.113.9", "Mozilla/5.0 (iPhone)", "https://news.example.com/", LocalDateTime.now());

        assertTrue(clickStatsService.consume(event), "first consume wins");
        assertFalse(clickStatsService.consume(event), "second consume is a dedup skip");

        // the relay drains the one row; the stub answer does not matter here
        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(1, deliveryCount("evt_" + eventId)));
        assertEquals(1, deliveryCount("evt_" + eventId));
    }

    @Test
    void redirectDoesNotDependOnEndpointReachability() throws Exception {
        String token = registerForToken();
        // port 1 on localhost: connection refused, the endpoint can never be reached
        var resp = rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:1/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());

        String code = createLink(token, "https://example.com/hook-down");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value(),
                "the 302 must never wait on or fail with the webhook");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
    }

    /** Decodes the tenantId claim from the test JWT (no signature check needed in-test). */
    private Long tenantIdOf(String token) {
        try {
            String[] parts = token.split(Pattern.quote("."));
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return om.readTree(payload).path("tenantId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot decode tenant from the test jwt", ex);
        }
    }
}

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * B-wave2 B5: click webhooks on the A7 outbox chain. The delivery row is written
 * inside the consume dedup transaction and drained by the relay with HMAC-signed
 * HTTP posts against a local JDK HttpServer stub (zero test dependencies; the
 * context enables allow-private-targets for exactly this).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-hook:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "linkforge.webhook.allow-private-targets=true",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class WebhookDeliveryIT extends AbstractIntegrationTest {

    private static HttpServer stub;
    /** Recorded requests in arrival order: [path, eventIdHeader, timestampHeader, signatureHeader, body]. */
    private static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger PORT = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClickStatsService clickStatsService;

    /** Status codes the stub returns, consumed front-to-back; the last one repeats. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Integer> SCRIPT =
            new ConcurrentLinkedQueue<>();

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        PORT.set(stub.getAddress().getPort());
        stub.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED.add(new String[]{
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_EVENT_ID),
                    exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_TIMESTAMP),
                    exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_SIGNATURE),
                    new String(body, StandardCharsets.UTF_8)});
            Integer scripted = SCRIPT.poll();
            int status = scripted != null ? scripted : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @BeforeEach
    void resetScriptAndRecords() {
        SCRIPT.clear();
        RECEIVED.clear();
    }

    private static org.springframework.http.HttpHeaders jsonHeaders(String token) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token.split("\\|")[0]);
        return headers;
    }

    private int deliveryCount(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_webhook_delivery WHERE event_id = ?", Integer.class, eventId);
    }

    private String deliveryStatus(String eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM lf_webhook_delivery WHERE event_id = ?", String.class, eventId);
    }

    @Test
    void signedDeliveryFlowsThroughTheRealOutboxChain() throws Exception {
        String token = registerForToken();
        var resp = rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + "/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String secret = body(resp).path("data").path("secret").asText();
        assertTrue(secret.startsWith("whsec_"));

        // at rest only ciphertext is stored
        String stored = jdbc.queryForObject("SELECT secret FROM lf_webhook_endpoint", String.class);
        assertTrue(stored.startsWith("v1:"), "secret must be AES-GCM ciphertext: " + stored);
        assertFalse(stored.contains(secret), "plaintext secret must never be stored");

        String code = createLink(token, "https://example.com/hook-hit");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> !RECEIVED.isEmpty());
        String[] request = RECEIVED.get(0);
        assertEquals("/hook", request[0]);
        assertTrue(request[1].startsWith("evt_log-"), "event id derives from the click eventId: " + request[1]);

        long timestamp = Long.parseLong(request[2]);
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - timestamp) <= 300, "timestamp within ±300s");
        assertEquals(WebhookSigner.sign(secret, timestamp, request[3] == null ? "" : request[4]), request[3],
                "signature must verify against the recorded timestamp + body");

        var payload = om.readTree(request[4]);
        assertEquals("link.clicked", payload.path("type").asText());
        assertEquals(code, payload.path("data").path("code").asText());
        assertFalse(payload.path("data").path("device").asText().isBlank());
        assertFalse(payload.path("data").path("refererHost").asText().isBlank());
        assertFalse(payload.has("ip") || payload.path("data").has("ip"), "raw IP is not pushed (privacy minimisation)");
        assertEquals(1, deliveryCount(request[1]));
    }

    @Test
    void retryableFailuresBackOffThenSentWithStableEventId() throws Exception {
        String token = registerForToken();
        var resp = rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + "/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);
        String secret = body(resp).path("data").path("secret").asText();

        SCRIPT.addAll(List.of(500, 500)); // third attempt (default 200) succeeds
        String code = createLink(token, "https://example.com/hook-retry");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
            String[] request = RECEIVED.stream().filter(r -> r[0].equals("/hook")).reduce((a, b) -> b).orElse(null);
            assertNotNull(request);
            assertEquals("SENT", deliveryStatus(request[1]), "third attempt must settle the delivery");
        });
        String eventId = RECEIVED.get(RECEIVED.size() - 1)[1];
        long firstEventIndex = RECEIVED.stream().map(r -> r[1]).toList().indexOf(eventId);
        assertTrue(RECEIVED.size() - firstEventIndex >= 3, "three attempts must have been made");
        assertTrue(RECEIVED.stream().allMatch(r -> r[1].equals(eventId)), "event id must stay stable across retries");

        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM lf_webhook_delivery WHERE event_id = ?", Integer.class, eventId);
        assertTrue(attempts != null && attempts >= 3, "attempts must count every try, got " + attempts);
        assertEquals(1, deliveryCount(eventId), "still exactly one delivery row");
    }

    @Test
    void permanent4xxFailsImmediatelyWithoutRetry() throws Exception {
        String token = registerForToken();
        rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + "/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);

        SCRIPT.add(400); // every attempt returns 400
        String code = createLink(token, "https://example.com/hook-400");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
            String[] request = RECEIVED.stream().filter(r -> r[0].equals("/hook")).findFirst().orElse(null);
            assertNotNull(request);
            assertEquals("FAILED", deliveryStatus(request[1]), "a 400 must fail the delivery on the first try");
        });
        String eventId = RECEIVED.get(0)[1];
        assertEquals(1, deliveryCount(eventId));
        Thread.sleep(1500); // give a would-be retry a chance to prove it does not happen
        assertEquals(1, RECEIVED.size(), "a permanent 4xx must not be retried");
    }

    @Test
    void maxAttemptsEndsInFailed() throws Exception {
        String token = registerForToken();
        rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + "/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);

        SCRIPT.add(500); // the last scripted status repeats forever
        String code = createLink(token, "https://example.com/hook-dead");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(40)).untilAsserted(() -> {
            String[] request = RECEIVED.stream().filter(r -> r[0].equals("/hook")).findFirst().orElse(null);
            assertNotNull(request);
            assertEquals("FAILED", deliveryStatus(request[1]), "exhausting max-attempts must end in FAILED");
        });
        String eventId = RECEIVED.get(0)[1];
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM lf_webhook_delivery WHERE event_id = ?", Integer.class, eventId);
        assertEquals(8, attempts, "attempts must stop at linkforge.webhook.max-attempts");
    }

    @Test
    void sameEventConsumedTwiceYieldsExactlyOneDelivery() throws Exception {
        String token = registerForToken();
        rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + "/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);

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
        rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:1/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);

        String code = createLink(token, "https://example.com/hook-down");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value(),
                "the 302 must never wait on or fail with the webhook");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
    }

    /** Decodes the tenantId claim from the test JWT (no signature check needed in-test). */
    private Long tenantIdOf(String token) throws Exception {
        String[] parts = token.split(Pattern.quote("."));
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        return om.readTree(payload).path("tenantId").asLong();
    }
}

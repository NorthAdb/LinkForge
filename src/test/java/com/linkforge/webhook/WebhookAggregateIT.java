package com.linkforge.webhook;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import com.linkforge.webhook.support.WebhookSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 wave C (D-C1-18..27): AGGREGATE webhook mode. A digest must land through the
 * SAME delivery machinery (enqueued row, relay, HMAC signature) when the window
 * expires OR the event threshold seals it early; late clicks join the next open
 * window flagged late; switching back to PER_CLICK flushes what is buffered; and
 * the PER_CLICK default keeps producing evt_ rows exactly as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-agg:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "linkforge.webhook.allow-private-targets=true",
        "linkforge.webhook.relay-enabled=true",
        "linkforge.webhook.aggregate-sweep-fixed-delay-ms=300",
        "linkforge.webhook.aggregate-sweep-initial-delay-ms=200",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class WebhookAggregateIT extends AbstractIntegrationTest {

    private static HttpServer stub;
    private static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger PORT = new AtomicInteger();
    private static final Map<String, ConcurrentLinkedQueue<Integer>> SCRIPTS = new ConcurrentHashMap<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClickStatsService clickStatsService;

    @Autowired
    private com.linkforge.webhook.support.WebhookSecretCipher cipher;

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        PORT.set(stub.getAddress().getPort());
        stub.createContext("/hook", WebhookAggregateIT::handle);
        stub.start();
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        RECEIVED.add(new String[]{
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_EVENT_ID),
                exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_TIMESTAMP),
                exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_SIGNATURE),
                new String(bodyBytes, StandardCharsets.UTF_8)});
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    private String registerTenant() throws Exception {
        return registerForToken();
    }

    private void putEndpoint(String token, String path, String deliveryMode,
                             Integer windowSeconds, Integer maxEvents) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", "http://127.0.0.1:" + PORT.get() + path);
        payload.put("enabled", true);
        if (deliveryMode != null) {
            payload.put("deliveryMode", deliveryMode);
        }
        if (windowSeconds != null) {
            payload.put("aggregateWindowSeconds", windowSeconds);
        }
        if (maxEvents != null) {
            payload.put("aggregateMaxEvents", maxEvents);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange("/api/webhooks/endpoint", HttpMethod.PUT,
                new HttpEntity<>(om.writeValueAsString(payload), headers), String.class);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    private void consumeClick(String tenantId, String eventId, String code, LocalDateTime clickedAt) {
        ClickEvent event = new ClickEvent(eventId, Long.valueOf(tenantId), 1L, code, 1L,
                "203.0.113.5", "Mozilla/5.0 (aggregate-it)", "https://news.example.com/", clickedAt);
        assertTrue(clickStatsService.consume(event), "consume must win: " + eventId);
    }

    private Long tenantIdOf(String token) {
        try {
            String[] parts = token.split(Pattern.quote("."));
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return om.readTree(payload).path("tenantId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot decode tenant from the test jwt", ex);
        }
    }

    private Map<String, Object> latestDeliveryFor(String token) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempts, event_id, event_type FROM lf_webhook_delivery "
                        + "WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", tenantIdOf(token));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void awaitLatestDigest(String token) {
        await().atMost(java.time.Duration.ofSeconds(90)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertTrue(String.valueOf(row.get("event_id")).startsWith("digest-"),
                    "latest row must be a digest, got: " + row);
        });
    }

    private String[] stubHitFor(String eventId) {
        return RECEIVED.stream().filter(r -> eventId.equals(r[1])).findFirst().orElse(null);
    }

    private String secretPlainFor(Long tenantId) {
        String stored = jdbc.queryForObject(
                "SELECT secret FROM lf_webhook_endpoint WHERE tenant_id = ?", String.class, tenantId);
        return cipher.decrypt(stored);
    }

    @Test
    void aggregateModeDeliversWindowDigestOnExpiry() throws Exception {
        String token = registerTenant();
        putEndpoint(token, "/hook-agg-window", "AGGREGATE", 15, null);

        String code = createLink(token, "https://example.com/agg-" + System.currentTimeMillis());
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        awaitLatestDigest(token);
        String digestId = String.valueOf(latestDeliveryFor(token).get("event_id"));
        assertTrue(digestId.matches("digest-\\d+-\\d+"), "event id shape digest-{endpointId}-{windowStart}: "
                + digestId);
        assertEquals("SENT", latestDeliveryFor(token).get("status"), "relay must send the digest");

        String[] hit = stubHitFor(digestId);
        assertNotNull(hit, "stub must have received " + digestId + ", got " + RECEIVED.size());
        long timestamp = Long.parseLong(hit[2]);
        assertEquals(WebhookSigner.sign(secretPlainFor(tenantIdOf(token)), timestamp, hit[4]), hit[3],
                "digest signature must verify exactly like link.clicked does");

        var payload = om.readTree(hit[4]);
        assertEquals("link.clicks.digest", payload.path("type").asText());
        assertEquals(code, payload.path("data").path("links").get(0).path("code").asText());
        assertTrue(payload.path("data").path("links").get(0).path("clicks").asInt() >= 1,
                "the bucket must count the click: " + hit[4]);
        assertFalse(hit[4].contains("\"ip\""), "digest payload must not carry raw IP");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_webhook_delivery WHERE event_id = ?", Integer.class, digestId),
                "the window digests exactly once");
    }

    @Test
    void eventThresholdSealsTheWindowBeforeExpiry() throws Exception {
        String token = registerTenant();
        Long tenantId = tenantIdOf(token);
        putEndpoint(token, "/hook-agg-threshold", "AGGREGATE", 300, 5);

        for (int i = 0; i < 5; i++) {
            consumeClick(String.valueOf(tenantId), "agg-thr-" + i + "-" + UUID.randomUUID(),
                    "agg-thr", LocalDateTime.now());
        }

        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertTrue(String.valueOf(row.get("event_id")).startsWith("digest-"),
                    "the 5th click must seal the 300s window long before it expires: " + row);
        });
    }

    @Test
    void lateClickJoinsTheNextWindowMarkedLate() throws Exception {
        String token = registerTenant();
        Long tenantId = tenantIdOf(token);
        putEndpoint(token, "/hook-agg-late", "AGGREGATE", 15, null);

        consumeClick(String.valueOf(tenantId), "agg-late-" + UUID.randomUUID(), "agg-late",
                LocalDateTime.now().minusSeconds(45));

        awaitLatestDigest(token);
        String digestId = String.valueOf(latestDeliveryFor(token).get("event_id"));
        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                assertNotNull(stubHitFor(digestId), "stub must receive the late digest"));
        String[] hit = stubHitFor(digestId);
        var links = om.readTree(hit[4]).path("data").path("links");
        assertTrue(links.size() > 0, "digest must carry at least one bucket: " + hit[4]);
        assertTrue(links.get(0).path("late").asBoolean(),
                "a click older than the open window must be flagged late: " + hit[4]);
    }

    @Test
    void switchingBackToPerClickFlushesTheBufferFirst() throws Exception {
        String token = registerTenant();
        Long tenantId = tenantIdOf(token);
        putEndpoint(token, "/hook-agg-switch", "AGGREGATE", 300, null);

        consumeClick(String.valueOf(tenantId), "agg-sw-" + UUID.randomUUID(), "agg-switch",
                LocalDateTime.now());
        consumeClick(String.valueOf(tenantId), "agg-sw-" + UUID.randomUUID(), "agg-switch",
                LocalDateTime.now());

        putEndpoint(token, "/hook-agg-switch", "PER_CLICK", null, null);

        awaitLatestDigest(token);
        assertTrue(String.valueOf(latestDeliveryFor(token).get("event_type")).equals("link.clicks.digest"),
                "the switch must flush the buffered window as a digest");

        consumeClick(String.valueOf(tenantId), "agg-sw-after-" + UUID.randomUUID(), "agg-switch",
                LocalDateTime.now());
        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row);
            assertTrue(String.valueOf(row.get("event_id")).startsWith("evt_"),
                    "after the switch new clicks go out per-click again: " + row);
        });
    }

    @Test
    void perClickDefaultStaysPerClick() throws Exception {
        String token = registerTenant();
        putEndpoint(token, "/hook-agg-default", null, null, null);

        String code = createLink(token, "https://example.com/perclick-" + System.currentTimeMillis());
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertTrue(String.valueOf(row.get("event_id")).startsWith("evt_"),
                    "PER_CLICK default must keep the per-click contract: " + row);
        });
    }
}

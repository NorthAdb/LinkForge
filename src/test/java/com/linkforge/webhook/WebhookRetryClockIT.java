package com.linkforge.webhook;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.webhook.support.WebhookSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-wave2-fix M1 + S2 regression, run in a dedicated failsafe fork pinned to
 * Asia/Shanghai (pom, execution webhook-tz-it) while the Testcontainers MySQL
 * stays on UTC — the exact JVM(+8)/DB(UTC) skew that made the old JVM-clock
 * backoff stretch to ~8h. Asserts:
 * - next_attempt_at is scheduled on the DB clock: TIMESTAMPDIFF(SECOND, NOW(),
 *   next_attempt_at) lands in [0, first backoff]; under the old implementation
 *   this diff was ~28800 and the retry fired ~8h late (red).
 * - business timestamps follow the JVM clock: sent_at >= created_at and
 *   last_used_at >= created_at even with the skew (old NOW()-based sent_at /
 *   last_used_at landed ~8h before their created_at; red).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-clock:",
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
class WebhookRetryClockIT extends AbstractIntegrationTest {

    private static HttpServer stub;
    private static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger PORT = new AtomicInteger();
    private static final ConcurrentLinkedQueue<Integer> SCRIPT = new ConcurrentLinkedQueue<>();

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        PORT.set(stub.getAddress().getPort());
        stub.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED.add(new String[]{exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_EVENT_ID),
                    new String(body, java.nio.charset.StandardCharsets.UTF_8)});
            Integer scripted = SCRIPT.poll();
            exchange.sendResponseHeaders(scripted != null ? scripted : 200, -1);
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

    private void configureEndpoint(String token) throws Exception {
        var resp = rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://127.0.0.1:" + PORT.get() + "/hook", "enabled", true)),
                        jsonHeaders(token)), String.class);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    private static org.springframework.http.HttpHeaders jsonHeaders(String token) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private Map<String, Object> latestDeliveryFor(String token) {
        Long tenantId = tenantIdOf(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempts, next_attempt_at, created_at, sent_at, event_id "
                        + "FROM lf_webhook_delivery WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Connector/J may hand DATETIME back as LocalDateTime or Timestamp depending on version. */
    private static java.time.LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt;
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private Long tenantIdOf(String token) {
        try {
            String[] parts = token.split(java.util.regex.Pattern.quote("."));
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return om.readTree(payload).path("tenantId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot decode tenant from the test jwt", ex);
        }
    }

    @Test
    void retryBackoffFollowsTheDbClockEvenWithSkewedJvm() throws Exception {
        String token = registerForToken();
        configureEndpoint(token);
        SCRIPT.add(500); // first attempt fails, the retry must be scheduled on the DB clock

        String code = createLink(token, "https://example.com/clock-retry");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertTrue(((Number) row.get("attempts")).intValue() >= 1,
                    "first attempt must have failed and scheduled a retry: " + row);
            assertEquals("PENDING", row.get("status"), "still waiting for the retry: " + row);
            // the decisive M1 assertion: DB-clock distance to the next attempt is at
            // most the first backoff (10s * 2^1 = 20s), never the ~8h JVM/DB skew
            Long diffSeconds = jdbc.queryForObject(
                    "SELECT TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at) FROM lf_webhook_delivery "
                            + "WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", Long.class, tenantIdOf(token));
            assertNotNull(diffSeconds);
            assertTrue(diffSeconds >= 0 && diffSeconds <= 20,
                    "next_attempt_at must sit within [0, 20]s of the DB NOW(), got " + diffSeconds
                            + "s — a JVM-clock schedule under UTC/UTC+8 skew reads ~28800s: row=" + row);
        });

        // and the retry must actually fire within seconds, not hours
        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertEquals("SENT", row.get("status"), "the DB-clock backoff must let the retry fire: " + row);
        });
    }

    @Test
    void businessTimestampsFollowTheJvmClockEvenWithSkewedJvm() throws Exception {
        String token = registerForToken();
        configureEndpoint(token);

        // key lifecycle: created (JVM) then used (last_used_at)
        var keyResp = postJson("/api/keys",
                om.writeValueAsString(java.util.Map.of("name", "clock")), token);
        assertEquals(200, keyResp.getStatusCode().value(), keyResp.getBody());
        String plaintext = body(keyResp).path("data").path("key").asText();
        var headers = new org.springframework.http.HttpHeaders();
        headers.set(com.linkforge.security.ApiKeyAuthFilter.API_KEY_HEADER, plaintext);
        rest.exchange("/api/links?page=1&size=1", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);

        Long keyId = body(keyResp).path("data").path("id").asLong();
        await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT created_at, last_used_at FROM lf_api_key WHERE id = ?", keyId);
            assertNotNull(row.get("last_used_at"), "the used key must have a last_used_at stamp: " + row);
            assertTrue(asLocalDateTime(row.get("last_used_at"))
                            .compareTo(asLocalDateTime(row.get("created_at"))) >= 0,
                    "last_used_at must not predate created_at under JVM/DB skew: " + row);
        });

        // delivery lifecycle: created (JVM) then sent (sent_at)
        SCRIPT.clear(); // default 200
        String code = createLink(token, "https://example.com/clock-ts");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertEquals("SENT", row.get("status"), "delivery row: " + row);
            assertTrue(asLocalDateTime(row.get("sent_at"))
                            .compareTo(asLocalDateTime(row.get("created_at"))) >= 0,
                    "sent_at must not predate created_at under JVM/DB skew: " + row);
        });
    }
}

package com.linkforge.observability;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1: /actuator/prometheus is guarded by the dedicated scrape token (fail-closed when
 * unset), API keys stay 403, the export carries the new counters/gauges plus the
 * http.server.requests histogram, and no per-request value (code, URL, IP) leaks into
 * a tag. @AutoConfigureObservability re-enables metrics export — @SpringBootTest
 * disables it by default (DisableObservabilityContextCustomizer), which would otherwise
 * remove the endpoint bean from every test context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class PrometheusScrapeIT extends AbstractIntegrationTest {

    static final String TOKEN = "test-scrape-token-not-secret";

    @DynamicPropertySource
    static void observabilityProperties(DynamicPropertyRegistry registry) {
        registry.add("linkforge.metrics.scrape-token", () -> TOKEN);
        registry.add("linkforge.metrics.backlog-sample-interval-ms", () -> "500");
        registry.add("linkforge.rate-limit.key-prefix", () -> "linkforge:rl:it-prom:");
        registry.add("linkforge.rate-limit.register-max", () -> "1000");
        registry.add("linkforge.rate-limit.create-max", () -> "200");
        registry.add("linkforge.rate-limit.redirect-max", () -> "1000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private ResponseEntity<String> scrape(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return rest.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void scrapeIsTokenGated() {
        assertEquals(401, scrape(null).getStatusCode().value(), "no token must be 401");
        assertEquals(401, scrape("Bearer wrong-token").getStatusCode().value(), "wrong token must be 401");
        ResponseEntity<String> ok = scrape("Bearer " + TOKEN);
        assertEquals(200, ok.getStatusCode().value(), "the configured token must scrape");
        assertTrue(ok.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE).contains("text/plain"),
                "prometheus text exposition, got " + ok.getHeaders().getContentType());
    }

    @Test
    void apiKeysStayForbidden() throws Exception {
        String token = registerForToken();
        var created = postJson("/api/keys", om.writeValueAsString(Map.of("name", "scrape-probe")), token);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String key = om.readTree(created.getBody()).path("data").path("key").asText();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        assertEquals(403, rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value());
    }

    @Test
    void exportCarriesTheNewMetrics() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/prom-" + unique(""));
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        String body = scrape("Bearer " + TOKEN).getBody();
        assertNotNull(body);
        assertTrue(body.contains("linkforge_redirects_total"), "redirects counter exported");
        assertTrue(body.contains("linkforge_cache_l2_total"), "L2 hit/miss counter exported (B1)");
        assertTrue(body.contains("linkforge_outbox_pending"), "outbox backlog gauge exported (B1)");
        assertTrue(body.contains("linkforge_webhook_pending"), "webhook backlog gauge exported (B1)");
        assertTrue(body.contains("http_server_requests_seconds_bucket"), "histogram enabled");
        assertTrue(body.contains("uri=\"/r/{code}\""), "the uri tag is the template, never a code");
        assertTrue(body.contains("application=\"linkforge\""), "common application tag");
    }

    @Test
    void tagsStayBounded() throws Exception {
        String token = registerForToken();
        String secret = unique("s");
        String code = createLink(token, "https://example.com/bounded-" + secret);
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        String body = scrape("Bearer " + TOKEN).getBody();
        assertNotNull(body);
        assertFalse(body.contains(code), "the short code must never appear in the export");
        assertFalse(body.contains(secret), "request-level values must never appear");
    }

    @Test
    void backlogGaugesCountTheRows() {
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO lf_outbox (event_id, payload, status) VALUES (?, ?, 'PENDING')",
                    "it-prom-" + UUID.randomUUID(), "{\"it\":true}");
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            String body = scrape("Bearer " + TOKEN).getBody();
            assertNotNull(body);
            assertTrue(moreThan(body, "linkforge_outbox_pending", 3),
                    "the sampled gauge must reflect the pending rows");
        });
    }

    private static boolean moreThan(String body, String metric, long min) {
        return body.lines()
                .filter(line -> line.startsWith(metric + " "))
                .mapToLong(line -> (long) Double.parseDouble(line.substring(metric.length() + 1)))
                .sum() >= min;
    }
}

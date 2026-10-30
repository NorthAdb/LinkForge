package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A8 end to end: redirects carrying different User-Agent / Referer headers flow
 * through the outbox to the consumer, which buckets them into lf_click_dim_daily
 * inside the idempotent transaction; the breakdown API exposes them owner-only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-dim:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true"
})
@ActiveProfiles("test")
class DimBreakdownIT extends AbstractIntegrationTest {

    @Test
    void breakdownBucketsDeviceAndRefererHost() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/dim-" + System.currentTimeMillis());

        getWithHeader("/r/" + code, null, "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)");
        getWithHeader("/r/" + code, null, "User-Agent",
                "Mozilla/5.0 (iPhone) Mobile Safari");
        getWithHeader("/r/" + code, null, "User-Agent", "curl/8.4.0");
        getWithHeader("/r/" + code, null, "Referer", "https://Www.Example.COM/landing");
        getWithHeader("/r/" + code, null, "Referer", "https://sub.other.com/campaign");

        String tok = token;
        await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(5,
                        body(get("/api/links/" + code + "/stats", tok)).path("data").path("totalClicks").asLong()));

        var device = body(get("/api/links/" + code + "/stats/breakdown?dim=device", tok)).path("data");
        assertEquals("mobile", device.get(0).path("dimValue").asText());
        assertEquals(2, device.get(0).path("clicks").asLong());
        assertEquals("unknown", device.get(1).path("dimValue").asText());
        assertEquals(2, device.get(1).path("clicks").asLong());
        assertEquals("bot", device.get(2).path("dimValue").asText());
        assertEquals(1, device.get(2).path("clicks").asLong());

        var referer = body(get("/api/links/" + code + "/stats/breakdown?dim=referer_host", tok)).path("data");
        assertEquals("direct", referer.get(0).path("dimValue").asText());
        assertEquals(3, referer.get(0).path("clicks").asLong());
        assertEquals("example.com", referer.get(1).path("dimValue").asText());
        assertEquals(1, referer.get(1).path("clicks").asLong());
        assertEquals("sub.other.com", referer.get(2).path("dimValue").asText());
        assertEquals(1, referer.get(2).path("clicks").asLong());
    }

    @Test
    void breakdownIsOwnerOnlyAndValidatesDim() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String code = createLink(tokenA, "https://example.com/dim-guard");

        assertEquals(404, get("/api/links/" + code + "/stats/breakdown?dim=device", tokenB)
                .getStatusCode().value(), "breakdown must be owner-only");
        var unknown = get("/api/links/" + code + "/stats/breakdown?dim=os", tokenA);
        assertEquals(400, unknown.getStatusCode().value(), "unsupported dim must be a 4xx");
        assertClientDimError(unknown.getBody());
        var missing = get("/api/links/" + code + "/stats/breakdown", tokenA);
        assertEquals(400, missing.getStatusCode().value(), "missing dim must be 400: " + missing.getBody());
        assertClientDimError(missing.getBody());
        var blank = get("/api/links/" + code + "/stats/breakdown?dim=", tokenA);
        assertEquals(400, blank.getStatusCode().value(), "blank dim must be 400: " + blank.getBody());
        assertEquals(200, get("/api/links/" + code + "/stats/breakdown?dim=device", tokenA)
                .getStatusCode().value());
    }

    private static void assertClientDimError(String body) {
        assertTrue(body.contains("\"code\":400"), body);
        assertTrue(body.contains("dim"), body);
        assertFalse(body.contains("Exception"), body);
        assertFalse(body.contains("\tat "), body);
    }
}

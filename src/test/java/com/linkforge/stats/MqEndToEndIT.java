package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real RabbitMQ path: redirect -> outbox -> relay publishes -> consumer aggregates
 * -> stats API reads. The poll window absorbs relay tick and consumer latency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-mqe2e:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true"
})
@ActiveProfiles("test")
class MqEndToEndIT extends AbstractIntegrationTest {

    @Test
    void redirectsAggregateThroughRealRabbit() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/mq-" + System.currentTimeMillis());

        for (int i = 0; i < 3; i++) {
            var redirect = get("/r/" + code, null);
            assertEquals(302, redirect.getStatusCode().value(), redirect.getBody());
        }
        assertTrue(pollTotal(code, token, 3), "3 clicks must aggregate via real MQ, last seen: "
                + get("/api/links/" + code + "/stats", token).getBody());

        for (int i = 0; i < 2; i++) {
            assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        }
        assertTrue(pollTotal(code, token, 5), "2 more clicks must aggregate via real MQ, last seen: "
                + get("/api/links/" + code + "/stats", token).getBody());
    }

    private boolean pollTotal(String code, String token, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            var stats = get("/api/links/" + code + "/stats", token);
            if (stats.getStatusCode().is2xxSuccessful()
                    && body(stats).path("data").path("totalClicks").asLong() == expected) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}

package com.linkforge.team;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 2b accept-invitation rate limit (plan 5.2, RefreshRateLimitIT
 * posture): the endpoint is public and credential-bearing, so it takes the
 * login-tier per-IP sliding window, and the limit precedes any token
 * validation — garbage tokens burn budget too, and the (max+1)-th attempt
 * is a 429 with Retry-After.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-accept-limit:",
        "linkforge.rate-limit.accept-invitation-max=3",
        "linkforge.rate-limit.accept-invitation-window-seconds=600"
})
@ActiveProfiles("test")
class AcceptInvitationRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearCounters() {
        var keys = redis.keys("linkforge:rl:it-accept-limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void acceptLimitPrecedesValidation() throws Exception {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> attempt = postJson("/api/auth/accept-invitation",
                    om.writeValueAsString(Map.of(
                            "token", "garbage-" + i,
                            "username", unique("probe"),
                            "password", "it-pass-123456")), null);
            assertEquals(401, attempt.getStatusCode().value(),
                    "call " + i + " is a validation 401, not yet limited: " + attempt.getBody());
        }
        ResponseEntity<String> limited = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", "garbage-4th",
                        "username", unique("probe"),
                        "password", "it-pass-123456")), null);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertEquals(429, body(limited).path("code").asInt());
        String retryAfter = limited.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 must carry Retry-After");
        int seconds = Integer.parseInt(retryAfter.trim());
        assertTrue(seconds >= 1 && seconds <= 600, "Retry-After " + seconds + " outside [1, 600]");
    }
}

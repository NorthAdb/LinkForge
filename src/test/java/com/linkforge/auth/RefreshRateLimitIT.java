package com.linkforge.auth;

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
 * Batch 2a rate limits (plan 5.2, LoginRateLimitIT posture): refresh and logout
 * are public, credential-bearing entries and take the login-tier per-IP sliding
 * window. The limit precedes any token validation — even garbage tokens burn
 * budget and the (max+1)-th call is a 429 with Retry-After.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-refresh-limit:",
        "linkforge.rate-limit.refresh-max=3",
        "linkforge.rate-limit.refresh-window-seconds=600",
        "linkforge.rate-limit.logout-max=3",
        "linkforge.rate-limit.logout-window-seconds=600"
})
@ActiveProfiles("test")
class RefreshRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearCounters() {
        var keys = redis.keys("linkforge:rl:it-refresh-limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void refreshLimitPrecedesValidation() throws Exception {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> attempt = postJson("/api/auth/refresh",
                    om.writeValueAsString(Map.of("refreshToken", "garbage-" + i)), null);
            assertEquals(401, attempt.getStatusCode().value(),
                    "call " + i + " is a validation 401, not yet limited: " + attempt.getBody());
        }
        ResponseEntity<String> limited = postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", "garbage-4th")), null);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertEquals(429, body(limited).path("code").asInt());
        String retryAfter = limited.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 must carry Retry-After");
        int seconds = Integer.parseInt(retryAfter.trim());
        assertTrue(seconds >= 1 && seconds <= 600, "Retry-After " + seconds + " outside [1, 600]");
    }

    @Test
    void logoutIsLimitedToo() throws Exception {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> attempt = postJson("/api/auth/logout",
                    om.writeValueAsString(Map.of("refreshToken", "garbage-" + i)), null);
            assertEquals(200, attempt.getStatusCode().value(),
                    "logout is idempotent, unknown tokens succeed: " + attempt.getBody());
        }
        ResponseEntity<String> limited = postJson("/api/auth/logout",
                om.writeValueAsString(Map.of("refreshToken", "garbage-4th")), null);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertNotNull(limited.getHeaders().getFirst("Retry-After"), "429 must carry Retry-After");
    }
}

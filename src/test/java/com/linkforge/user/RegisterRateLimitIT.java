package com.linkforge.user;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-rl:",
        "linkforge.rate-limit.register-max=2",
        "linkforge.rate-limit.register-window-seconds=10"
})
@ActiveProfiles("test")
class RegisterRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearCounter() {
        var keys = redis.keys("linkforge:rl:it-rl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void thirdRegisterFromSameIpGets429() throws Exception {
        for (int i = 0; i < 2; i++) {
            var ok = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                    "tenantName", unique("rl" + i), "username", unique("u"), "password", "it-pass-123456")), null);
            assertEquals(200, ok.getStatusCode().value(), "call " + i + ": " + ok.getBody());
        }
        var limited = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("rlx"), "username", unique("u"), "password", "it-pass-123456")), null);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertEquals(429, body(limited).path("code").asInt());
        String retryAfter = limited.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 must carry Retry-After");
        int seconds = Integer.parseInt(retryAfter.trim());
        assertTrue(seconds >= 1 && seconds <= 10, "Retry-After " + seconds + " outside [1, 10]");
    }
}

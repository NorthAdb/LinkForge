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

/**
 * Audit H1: the login endpoint takes the same sliding-window per-IP limit as
 * registration. Distinct accounts per attempt isolate the IP bucket (the
 * account bucket is raised out of the way in this class's properties).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-login:",
        "linkforge.rate-limit.login-max=2",
        "linkforge.rate-limit.login-window-seconds=10",
        "linkforge.rate-limit.login-account-max=100",
        "linkforge.rate-limit.login-account-window-seconds=10"
})
@ActiveProfiles("test")
class LoginRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearCounter() {
        var keys = redis.keys("linkforge:rl:it-login:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void registerTenant(String tenant, String username) throws Exception {
        var ok = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenant, "username", username, "password", "it-pass-123456")), null);
        assertEquals(200, ok.getStatusCode().value(), ok.getBody());
    }

    @Test
    void thirdLoginFromSameIpGets429() throws Exception {
        String[] tenants = {unique("li1"), unique("li2"), unique("li3")};
        for (String tenant : tenants) {
            registerTenant(tenant, "admin");
        }
        for (int i = 0; i < 2; i++) {
            var attempt = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                    "tenantName", tenants[i], "username", "admin", "password", "wrong-password")), null);
            assertEquals(401, attempt.getStatusCode().value(), "call " + i + ": " + attempt.getBody());
        }
        var limited = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenants[2], "username", "admin", "password", "wrong-password")), null);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertEquals(429, body(limited).path("code").asInt());
        String retryAfter = limited.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 must carry Retry-After");
        int seconds = Integer.parseInt(retryAfter.trim());
        assertTrue(seconds >= 1 && seconds <= 10, "Retry-After " + seconds + " outside [1, 10]");
    }
}

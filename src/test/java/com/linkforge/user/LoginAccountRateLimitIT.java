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

/**
 * Audit H1, second gate: login attempts are also limited per account
 * (tenant + username), so rotating source IPs cannot reset the budget.
 * The IP bucket is raised out of the way in this class's properties, and a
 * different account must stay unaffected (401, not 429).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-login-acct:",
        "linkforge.rate-limit.login-max=100",
        "linkforge.rate-limit.login-window-seconds=10",
        "linkforge.rate-limit.login-account-max=2",
        "linkforge.rate-limit.login-account-window-seconds=10"
})
@ActiveProfiles("test")
class LoginAccountRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearCounter() {
        var keys = redis.keys("linkforge:rl:it-login-acct:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private org.springframework.http.ResponseEntity<String> login(String tenant, String password) throws Exception {
        return postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenant, "username", "admin", "password", password)), null);
    }

    @Test
    void thirdLoginOnSameAccountGets429ButOtherAccountUnaffected() throws Exception {
        String target = unique("acct-target");
        String bystander = unique("acct-other");
        for (String tenant : new String[]{target, bystander}) {
            var ok = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                    "tenantName", tenant, "username", "admin", "password", "it-pass-123456")), null);
            assertEquals(200, ok.getStatusCode().value(), ok.getBody());
        }
        for (int i = 0; i < 2; i++) {
            var attempt = login(target, "wrong-password");
            assertEquals(401, attempt.getStatusCode().value(), "call " + i + ": " + attempt.getBody());
        }
        var limited = login(target, "wrong-password");
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());

        var other = login(bystander, "wrong-password");
        assertEquals(401, other.getStatusCode().value(), other.getBody());
    }
}

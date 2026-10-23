package com.linkforge.user;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-rl:",
        "linkforge.rate-limit.register-max=2",
        "linkforge.rate-limit.register-window-seconds=2"
})
@ActiveProfiles("test")
class RegisterRateLimitTest extends AbstractIntegrationTest {

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
    }
}

package com.linkforge.user;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-flow:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class RegisterFlowTest extends AbstractIntegrationTest {

    @Test
    void registerReturnsUsableToken() throws Exception {
        String token = registerForToken();
        var resp = get("/api/links?page=1&size=10", token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void duplicateTenantNameRejected() throws Exception {
        String tenantName = unique("dup");
        String first = om.writeValueAsString(Map.of("tenantName", tenantName,
                "username", unique("u"), "password", "it-pass-123456"));
        var ok = postJson("/api/auth/register", first, null);
        assertEquals(200, ok.getStatusCode().value(), ok.getBody());

        String second = om.writeValueAsString(Map.of("tenantName", tenantName,
                "username", unique("u"), "password", "it-pass-123456"));
        var conflict = postJson("/api/auth/register", second, null);
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertEquals(409, body(conflict).path("code").asInt());
    }

    @Test
    void weakPasswordRejected() throws Exception {
        String json = om.writeValueAsString(Map.of("tenantName", unique("wk"),
                "username", unique("u"), "password", "short7"));
        var resp = postJson("/api/auth/register", json, null);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void invalidTenantCharsRejected() throws Exception {
        String json = om.writeValueAsString(Map.of("tenantName", "bad!name",
                "username", unique("u"), "password", "it-pass-123456"));
        var resp = postJson("/api/auth/register", json, null);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void malformedBodyRejected() {
        var resp = postJson("/api/auth/register", "{not-json", null);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
        assertTrue(resp.getBody().contains("malformed"), resp.getBody());
    }
}

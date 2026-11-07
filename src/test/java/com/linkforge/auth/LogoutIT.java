package com.linkforge.auth;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Batch 2a logout semantics (plan 5.1): single logout revokes exactly one
 * family — its refresh dies, other sessions and the (short-lived) access token
 * are untouched; logout-all revokes every refresh AND bumps token_version, so
 * access tokens die on their next request too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogoutIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    private String tenantName;
    private String username;

    private record Pair(String access, String refresh) {
    }

    private Pair registerPair() throws Exception {
        tenantName = unique("lo");
        username = unique("u");
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Pair(data.path("token").asText(), data.path("refreshToken").asText());
    }

    private Pair loginPair() throws Exception {
        ResponseEntity<String> resp = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Pair(data.path("token").asText(), data.path("refreshToken").asText());
    }

    private ResponseEntity<String> logoutCall(String rawRefresh) throws Exception {
        return postJson("/api/auth/logout",
                om.writeValueAsString(Map.of("refreshToken", rawRefresh)), null);
    }

    private ResponseEntity<String> refreshCall(String rawRefresh) throws Exception {
        return postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", rawRefresh)), null);
    }

    @Test
    void logoutKillsOnlyThatFamily() throws Exception {
        registerPair();
        Pair sessionA = loginPair();
        Pair sessionB = loginPair();

        assertEquals(200, logoutCall(sessionA.refresh()).getStatusCode().value());
        assertEquals(401, refreshCall(sessionA.refresh()).getStatusCode().value(),
                "the logged-out family's refresh is dead");
        assertEquals(200, get(LINKS, sessionA.access()).getStatusCode().value(),
                "single logout does not bump tv: the access token lives out its hour");
        assertEquals(200, refreshCall(sessionB.refresh()).getStatusCode().value(),
                "the other session is untouched");

        assertEquals(200, logoutCall("never-issued-token").getStatusCode().value(),
                "logout is idempotent: an unknown token is also a success (no oracle)");
    }

    @Test
    void logoutAllKillsEverySessionAndAccess() throws Exception {
        registerPair();
        Pair sessionA = loginPair();
        Pair sessionB = loginPair();

        ResponseEntity<String> resp = postJson("/api/auth/logout-all", "{}", sessionA.access());
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());

        assertEquals(401, get(LINKS, sessionA.access()).getStatusCode().value(),
                "tv bump kills the caller's access on its next request");
        assertEquals(401, get(LINKS, sessionB.access()).getStatusCode().value(),
                "tv bump kills every session's access");
        assertEquals(401, refreshCall(sessionA.refresh()).getStatusCode().value());
        assertEquals(401, refreshCall(sessionB.refresh()).getStatusCode().value());
    }

    @Test
    void logoutAllRequiresAuthentication() throws Exception {
        registerPair();
        ResponseEntity<String> resp = postJson("/api/auth/logout-all", "{}", null);
        assertEquals(401, resp.getStatusCode().value(), resp.getBody());
    }
}

package com.linkforge.auth;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Batch 2a password change (plan 5.1): a credential change is a full sign-out
 * event — token_version bumps and every refresh row is revoked, including the
 * caller's own session; the old password stops working and the new one logs in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChangePasswordIT extends AbstractIntegrationTest {

    private static final String OLD_PASSWORD = "it-pass-123456";
    private static final String NEW_PASSWORD = "new-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    private String tenantName;
    private String username;

    private record Pair(String access, String refresh) {
    }

    private Pair registerPair() throws Exception {
        tenantName = unique("cp");
        username = unique("u");
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", OLD_PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Pair(data.path("token").asText(), data.path("refreshToken").asText());
    }

    private ResponseEntity<String> loginAttempt(String password) throws Exception {
        return postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", password)), null);
    }

    private Pair loginPair() throws Exception {
        ResponseEntity<String> resp = loginAttempt(OLD_PASSWORD);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Pair(data.path("token").asText(), data.path("refreshToken").asText());
    }

    private ResponseEntity<String> changePassword(String access, String current, String next) throws Exception {
        return postJson("/api/auth/change-password", om.writeValueAsString(Map.of(
                "currentPassword", current, "newPassword", next)), access);
    }

    private ResponseEntity<String> refreshCall(String rawRefresh) throws Exception {
        return postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", rawRefresh)), null);
    }

    @Test
    void wrongCurrentPasswordRejected() throws Exception {
        Pair pair = registerPair();
        ResponseEntity<String> resp = changePassword(pair.access(), "not-the-password-1", NEW_PASSWORD);
        assertEquals(401, resp.getStatusCode().value(), resp.getBody());
        assertEquals(200, loginAttempt(OLD_PASSWORD).getStatusCode().value(),
                "a rejected change must leave the old password in place");
    }

    @Test
    void shortNewPasswordRejected() throws Exception {
        Pair pair = registerPair();
        ResponseEntity<String> resp = changePassword(pair.access(), OLD_PASSWORD, "short7");
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void changePasswordRevokesEverySession() throws Exception {
        Pair registered = registerPair();
        Pair sessionA = loginPair();
        Pair sessionB = loginPair();

        ResponseEntity<String> resp = changePassword(sessionA.access(), OLD_PASSWORD, NEW_PASSWORD);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());

        assertEquals(401, get(LINKS, sessionA.access()).getStatusCode().value(),
                "the caller's own access token dies too (tv bump)");
        assertEquals(401, get(LINKS, sessionB.access()).getStatusCode().value(),
                "every other session's access token dies");
        assertEquals(401, refreshCall(sessionA.refresh()).getStatusCode().value());
        assertEquals(401, refreshCall(sessionB.refresh()).getStatusCode().value());
        assertEquals(401, refreshCall(registered.refresh()).getStatusCode().value(),
                "the registration session's refresh dies as well");

        assertEquals(401, loginAttempt(OLD_PASSWORD).getStatusCode().value(),
                "the old password no longer logs in");
        ResponseEntity<String> relogin = loginAttempt(NEW_PASSWORD);
        assertEquals(200, relogin.getStatusCode().value(), relogin.getBody());
        String newAccess = body(relogin).path("data").path("token").asText();
        assertEquals(200, get(LINKS, newAccess).getStatusCode().value());
    }
}

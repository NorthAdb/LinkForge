package com.linkforge.team;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 2b admin password reset (plan 5.1, D3 — the batch's recovery path):
 * the admin gets a one-time temporary password to hand over; the member's old
 * password and every live session die immediately; the temporary password
 * logs in with mustChangePassword=true and the account stays behind the
 * forced-change wall until the member picks a real password.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminResetPasswordIT extends AbstractIntegrationTest {

    private static final String MEMBER_PASSWORD = "it-member-pass-1";
    private static final String NEW_PASSWORD = "it-new-pass-123";
    private static final String LINKS = "/api/links?page=1&size=10";

    @Autowired
    private UserAccountMapper userAccountMapper;

    private ResponseEntity<String> login(String tenantName, String username, String password) throws Exception {
        return postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", password)), null);
    }

    @Test
    void resetKillsOldCredentialsAndForcesChange() throws Exception {
        String tenantName = unique("rst");
        ResponseEntity<String> reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", unique("owner"),
                "password", "it-pass-123456")), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        String founderToken = body(reg).path("data").path("token").asText();

        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), founderToken);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String memberName = unique("member");
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", body(created).path("data").path("token").asText(),
                        "username", memberName, "password", MEMBER_PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        long memberId = body(accepted).path("data").path("userId").asLong();
        String memberRefresh = body(accepted).path("data").path("refreshToken").asText();

        ResponseEntity<String> reset = postJson(
                "/api/tenant/members/" + memberId + "/reset-password", "{}", founderToken);
        assertEquals(200, reset.getStatusCode().value(), reset.getBody());
        String temporary = body(reset).path("data").path("temporaryPassword").asText();
        assertFalse(temporary.isBlank(), "the temporary password is shown exactly once: here");
        assertTrue(temporary.length() >= 12, "temporary password length: " + temporary.length());

        // the member's old world is gone: old password and live refresh both die
        assertEquals(401, login(tenantName, memberName, MEMBER_PASSWORD).getStatusCode().value(),
                "old password after reset");
        assertEquals(401, postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", memberRefresh)), null)
                .getStatusCode().value(), "pre-reset refresh token");

        // the temporary password logs in, flagged, and hits the forced-change wall
        ResponseEntity<String> tempLogin = login(tenantName, memberName, temporary);
        assertEquals(200, tempLogin.getStatusCode().value(), tempLogin.getBody());
        assertEquals(Boolean.TRUE, body(tempLogin).path("data").path("mustChangePassword").asBoolean());
        String tempAccess = body(tempLogin).path("data").path("token").asText();
        ResponseEntity<String> blocked = get(LINKS, tempAccess);
        assertEquals(403, blocked.getStatusCode().value(), blocked.getBody());
        assertEquals("PASSWORD_CHANGE_REQUIRED", body(blocked).path("message").asText());

        // changing the password (temporary as current) clears the flag; the
        // change itself is a full sign-out, so the member logs in fresh
        ResponseEntity<String> changed = postJson("/api/auth/change-password",
                om.writeValueAsString(Map.of(
                        "currentPassword", temporary, "newPassword", NEW_PASSWORD)), tempAccess);
        assertEquals(200, changed.getStatusCode().value(), changed.getBody());
        assertEquals(0, userAccountMapper.selectById(memberId).getMustChangePassword(),
                "the forced-change flag clears with the change");

        ResponseEntity<String> freshLogin = login(tenantName, memberName, NEW_PASSWORD);
        assertEquals(200, freshLogin.getStatusCode().value(), freshLogin.getBody());
        assertEquals(Boolean.FALSE, body(freshLogin).path("data").path("mustChangePassword").asBoolean());
        assertEquals(200, get(LINKS, body(freshLogin).path("data").path("token").asText())
                .getStatusCode().value(), "back to normal behind the new password");
    }
}

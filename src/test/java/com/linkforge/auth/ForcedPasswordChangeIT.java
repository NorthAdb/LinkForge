package com.linkforge.auth;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Batch 2a forced password change (plan 5.1, self-audit A9 — a hard filter
 * block, not a polite hint): with must_change_password=1 every authenticated
 * request except change-password / logout / refresh answers 403
 * PASSWORD_CHANGE_REQUIRED, and changing the password clears the flag.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ForcedPasswordChangeIT extends AbstractIntegrationTest {

    private static final String OLD_PASSWORD = "it-pass-123456";
    private static final String NEW_PASSWORD = "new-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    @Autowired
    private UserAccountMapper userAccountMapper;

    private String tenantName;
    private String username;
    private long userId;

    @Test
    void flagBlocksEverythingExceptChangeLogoutRefresh() throws Exception {
        tenantName = unique("fpc");
        username = unique("u");
        ResponseEntity<String> reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", OLD_PASSWORD)), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        var data = body(reg).path("data");
        String access = data.path("token").asText();
        String refresh = data.path("refreshToken").asText();
        userId = data.path("userId").asLong();

        UserAccount user = userAccountMapper.selectById(userId);
        user.setMustChangePassword(1);
        userAccountMapper.updateById(user);

        ResponseEntity<String> blocked = get(LINKS, access);
        assertEquals(403, blocked.getStatusCode().value(), blocked.getBody());
        assertEquals(403, body(blocked).path("code").asInt());
        assertEquals("PASSWORD_CHANGE_REQUIRED", body(blocked).path("message").asText());

        // refresh is NOT blocked while the flag is set (and the filter exempts it)
        ResponseEntity<String> refreshed = postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", refresh)), access);
        assertEquals(200, refreshed.getStatusCode().value(), refreshed.getBody());

        // logout is NOT blocked either
        ResponseEntity<String> relogin = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", OLD_PASSWORD)), null);
        assertEquals(200, relogin.getStatusCode().value(), relogin.getBody());
        String secondRefresh = body(relogin).path("data").path("refreshToken").asText();
        assertEquals(Boolean.TRUE, body(relogin).path("data").path("mustChangePassword").asBoolean(),
                "login must surface the flag so the console can force the dialog");
        ResponseEntity<String> logout = postJson("/api/auth/logout",
                om.writeValueAsString(Map.of("refreshToken", secondRefresh)),
                body(relogin).path("data").path("token").asText());
        assertEquals(200, logout.getStatusCode().value(), logout.getBody());

        // change-password is the way out
        ResponseEntity<String> changed = postJson("/api/auth/change-password", om.writeValueAsString(Map.of(
                "currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD)), access);
        assertEquals(200, changed.getStatusCode().value(), changed.getBody());
        assertEquals(0, userAccountMapper.selectById(userId).getMustChangePassword(),
                "the flag clears on a successful change");

        ResponseEntity<String> freshLogin = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", NEW_PASSWORD)), null);
        assertEquals(200, freshLogin.getStatusCode().value(), freshLogin.getBody());
        String freshAccess = body(freshLogin).path("data").path("token").asText();
        assertEquals(200, get(LINKS, freshAccess).getStatusCode().value(),
                "after the forced change the account is fully usable again");
    }
}

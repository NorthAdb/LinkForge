package com.linkforge.apikey;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.apikey.dto.ApiKeyDtos;
import com.linkforge.apikey.service.ApiKeyService;
import com.linkforge.common.exception.BizException;
import com.linkforge.security.ApiKeyAuthFilter;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.user.service.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M7 contract: an admin password reset is compromise recovery. The
 * forced-change wall covers API keys, not just JWT sessions; the reset
 * permanently revokes every key the member holds (USER and TENANT scope);
 * and key creation is serialized with the reset on the user row, so no
 * racing creation can leave a live key behind the wall. Replaces the audit
 * probe {@code forcedPasswordChangeDoesNotBlockApiKey} (which asserted the
 * BAD behavior) with the correct contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiKeyForcedPasswordChangeIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String NEW_PASSWORD = "new-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private TeamService teamService;

    private record Tenant(String name, String founderToken, long founderId, long tenantId) {
    }

    private record Joined(long userId, String username, String token) {
    }

    private record Minted(String plaintext, String prefix) {
    }

    private Tenant registerTenant() throws Exception {
        String tenantName = unique("m7");
        ResponseEntity<String> reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", unique("owner"), "password", PASSWORD)), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        var data = body(reg).path("data");
        return new Tenant(tenantName, data.path("token").asText(),
                data.path("userId").asLong(), data.path("tenantId").asLong());
    }

    private Joined join(Tenant tenant, String role) throws Exception {
        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", role)), tenant.founderToken());
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String username = unique("member");
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", body(created).path("data").path("token").asText(),
                        "username", username, "password", PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        var data = body(accepted).path("data");
        return new Joined(data.path("userId").asLong(), username, data.path("token").asText());
    }

    /** The key owner is a second ADMIN so it can hold BOTH scopes (a plain
     * MEMBER cannot mint TENANT keys — that guard is TenantApiKeyIT's). */
    private Minted createKey(String jwt, String scope) throws Exception {
        ResponseEntity<String> created = postJson("/api/keys",
                om.writeValueAsString(Map.of("name", "m7-" + scope.toLowerCase(), "scope", scope)), jwt);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        var data = body(created).path("data");
        return new Minted(data.path("key").asText(), data.path("prefix").asText());
    }

    private ResponseEntity<String> getWithKey(String uri, String apiKey) {
        return getWithHeader(uri, null, ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
    }

    private ResponseEntity<String> postWithKey(String uri, String json, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private String reset(Tenant tenant, long targetId) throws Exception {
        ResponseEntity<String> reset = postJson(
                "/api/tenant/members/" + targetId + "/reset-password", "{}", tenant.founderToken());
        assertEquals(200, reset.getStatusCode().value(), reset.getBody());
        return body(reset).path("data").path("temporaryPassword").asText();
    }

    private boolean keyRevoked(String prefix) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_api_key WHERE key_prefix = ? AND revoked_at IS NOT NULL",
                Long.class, prefix);
        return count != null && count == 1;
    }

    @Test
    void resetBlocksExistingKeys() throws Exception {
        Tenant tenant = registerTenant();
        Joined target = join(tenant, "ADMIN");
        Minted userKey = createKey(target.token(), "USER");
        Minted tenantKey = createKey(target.token(), "TENANT");
        assertEquals(200, getWithKey(LINKS, userKey.plaintext()).getStatusCode().value(),
                "USER key works before the reset");
        assertEquals(200, getWithKey(LINKS, tenantKey.plaintext()).getStatusCode().value(),
                "TENANT key works before the reset");

        reset(tenant, target.userId());

        Integer mustChange = jdbcTemplate.queryForObject(
                "SELECT must_change_password FROM lf_user WHERE id = ?", Integer.class, target.userId());
        assertEquals(1, mustChange, "the reset sets the forced-change flag");
        assertTrue(keyRevoked(userKey.prefix()), "the reset revokes the USER key in the same commit");
        assertTrue(keyRevoked(tenantKey.prefix()), "the reset revokes the TENANT key in the same commit");

        // tombstone distinction: a KNOWN revoked key + owner behind the wall
        // gets the wall's own 403 (identical body to the JWT side), not a 401
        ResponseEntity<String> read = getWithKey(LINKS, userKey.plaintext());
        assertEquals(403, read.getStatusCode().value(), read.getBody());
        assertEquals("PASSWORD_CHANGE_REQUIRED", body(read).path("message").asText());
        ResponseEntity<String> tenantRead = getWithKey(LINKS, tenantKey.plaintext());
        assertEquals(403, tenantRead.getStatusCode().value(), tenantRead.getBody());
        assertEquals("PASSWORD_CHANGE_REQUIRED", body(tenantRead).path("message").asText());
        ResponseEntity<String> write = postWithKey("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/must-not-exist")),
                tenantKey.plaintext());
        assertEquals(403, write.getStatusCode().value(), write.getBody());
        Long leaked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_short_link WHERE original_url = 'https://example.com/must-not-exist'",
                Long.class);
        assertEquals(0, leaked, "a walled write must have no business effect");
    }

    @Test
    void oldKeysStayRevokedAfterPasswordChange() throws Exception {
        Tenant tenant = registerTenant();
        Joined target = join(tenant, "MEMBER");
        Minted key = createKey(target.token(), "USER");
        assertEquals(200, getWithKey(LINKS, key.plaintext()).getStatusCode().value());

        String temporary = reset(tenant, target.userId());

        ResponseEntity<String> tempLogin = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenant.name(), "username", target.username(),
                "password", temporary)), null);
        assertEquals(200, tempLogin.getStatusCode().value(), tempLogin.getBody());
        String tempAccess = body(tempLogin).path("data").path("token").asText();
        ResponseEntity<String> changed = postJson("/api/auth/change-password",
                om.writeValueAsString(Map.of(
                        "currentPassword", temporary, "newPassword", NEW_PASSWORD)), tempAccess);
        assertEquals(200, changed.getStatusCode().value(), changed.getBody());

        // the wall is down, the key is NOT back: revoked is permanent, the
        // tombstone degrades to the uniform 401 of any dead key
        ResponseEntity<String> oldKey = getWithKey(LINKS, key.plaintext());
        assertEquals(401, oldKey.getStatusCode().value(), oldKey.getBody());
        assertTrue(keyRevoked(key.prefix()), "the revocation does not roll back with the change");

        ResponseEntity<String> freshLogin = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenant.name(), "username", target.username(),
                "password", NEW_PASSWORD)), null);
        assertEquals(200, freshLogin.getStatusCode().value(), freshLogin.getBody());
        Minted fresh = createKey(body(freshLogin).path("data").path("token").asText(), "USER");
        assertEquals(200, getWithKey(LINKS, fresh.plaintext()).getStatusCode().value(),
                "a key minted after the change works normally");
    }

    @Test
    void keyCreationRacingResetLeavesNoLiveKey() throws Exception {
        Tenant tenant = registerTenant();
        Joined target = join(tenant, "MEMBER");
        AuthUser memberPrincipal = new AuthUser(target.userId(), tenant.tenantId(),
                target.username(), null);
        AuthUser founderPrincipal = new AuthUser(tenant.founderId(), tenant.tenantId(),
                "owner", null);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Object> createOutcome = new AtomicReference<>();
        AtomicReference<Throwable> resetFailure = new AtomicReference<>();
        Thread creator = new Thread(() -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
                ApiKeyDtos.CreateRequest request = new ApiKeyDtos.CreateRequest();
                request.setName("m7-race");
                createOutcome.set(apiKeyService.create(request, memberPrincipal));
            } catch (Throwable ex) {
                createOutcome.set(ex);
            }
        });
        Thread resetter = new Thread(() -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
                UserContext.set(founderPrincipal);
                try {
                    teamService.resetPassword(target.userId());
                } finally {
                    UserContext.clear();
                }
            } catch (Throwable ex) {
                resetFailure.set(ex);
            }
        });
        creator.start();
        resetter.start();
        creator.join(TimeUnit.SECONDS.toMillis(60));
        resetter.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(creator.isAlive() || resetter.isAlive(), "race threads stuck");
        assertNull(resetFailure.get(), "the reset itself must succeed: " + resetFailure.get());
        Object outcome = createOutcome.get();
        assertTrue(outcome instanceof ApiKeyDtos.Created || outcome instanceof BizException,
                "creation either landed before the reset or was refused by it, got: " + outcome);
        if (outcome instanceof BizException refused) {
            assertEquals(403, refused.getHttpStatus(),
                    "a creation losing the race is refused by the forced-change wall");
        }
        Long live = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_api_key WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, target.userId());
        assertEquals(0, live,
                "whichever side won the lock, no live key may survive the reset");
    }

    @Test
    void unknownKeyStill401() {
        ResponseEntity<String> resp = getWithKey(LINKS, "lfk_zzzzzzzz_zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
        assertEquals(401, resp.getStatusCode().value(), resp.getBody());
    }
}

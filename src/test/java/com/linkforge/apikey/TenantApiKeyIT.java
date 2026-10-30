package com.linkforge.apikey;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 wave A (D-C1-11..17): tenant-scope API keys. Pins the observable contract:
 * TENANT keys are minted only by a tenant ADMIN's JWT, see every link in their own
 * tenant (and nothing outside it), USER keys stay strictly per-user, and keys can
 * never touch the admin surface regardless of scope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-tenantkey:",
        "linkforge.rate-limit.register-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class TenantApiKeyIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String API_KEY_HEADER = "X-API-Key";

    /** Tenant founder (ADMIN) with token, user id, tenant id and tenant name. */
    private record Founder(String token, long userId, long tenantId, String tenantName) {
    }

    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Founder registerFounder() throws Exception {
        String json = om.writeValueAsString(Map.of(
                "tenantName", unique("tk-tenant"),
                "username", unique("tk-owner"),
                "password", PASSWORD));
        ResponseEntity<String> resp = postJson("/api/auth/register", json, null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Founder(data.path("token").asText(), data.path("userId").asLong(),
                data.path("tenantId").asLong(), data.path("tenantName").asText());
    }

    /** Inserts an extra MEMBER into an existing tenant and logs them in. */
    private String seedMemberAndLogin(Founder founder) throws Exception {
        UserAccount member = new UserAccount();
        member.setTenantId(founder.tenantId());
        member.setUsername(unique("tk-member"));
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setStatus(1);
        member.setDeleted(0);
        member.setCreatedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.insert(member);

        String json = om.writeValueAsString(Map.of(
                "tenantName", founder.tenantName(),
                "username", member.getUsername(),
                "password", PASSWORD));
        ResponseEntity<String> resp = postJson("/api/auth/login", json, null);
        assertEquals(200, resp.getStatusCode().value(), "member login failed: " + resp.getBody());
        return body(resp).path("data").path("token").asText();
    }

    private ResponseEntity<String> createKey(String token, String name, String scope) throws Exception {
        Map<String, Object> payload = scope == null
                ? Map.of("name", name)
                : Map.of("name", name, "scope", scope);
        return postJson("/api/keys", om.writeValueAsString(payload), token);
    }

    private String createKeyPlaintext(String token, String name, String scope) throws Exception {
        ResponseEntity<String> resp = createKey(token, name, scope);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data").path("key").asText();
    }

    @Test
    void tenantAdminMintsTenantKeyButMemberIsRejected() throws Exception {
        Founder founder = registerFounder();

        ResponseEntity<String> created = createKey(founder.token(), "ops-tenant", "TENANT");
        assertEquals(200, created.getStatusCode().value(), "ADMIN JWT creating TENANT key: " + created.getBody());
        var data = body(created).path("data");
        assertEquals("TENANT", data.path("scope").asText(), "created key must carry scope=TENANT: " + created.getBody());
        assertEquals(founder.userId(), data.path("createdBy").asLong(), "create view must expose its creator");
        assertTrue(data.path("prefix").asText().startsWith("lfk_"), created.getBody());

        String memberToken = seedMemberAndLogin(founder);
        ResponseEntity<String> memberTenantAttempt = createKey(memberToken, "sneaky", "TENANT");
        assertEquals(403, memberTenantAttempt.getStatusCode().value(),
                "MEMBER JWT creating TENANT key must be 403: " + memberTenantAttempt.getBody());

        ResponseEntity<String> memberUserKey = createKey(memberToken, "plain-user-scope", null);
        assertEquals(200, memberUserKey.getStatusCode().value(),
                "USER-scope key stays open to every JWT user: " + memberUserKey.getBody());
        assertEquals("USER", body(memberUserKey).path("data").path("scope").asText(), memberUserKey.getBody());
    }

    @Test
    void tenantKeySeesWholeTenantWhileUserKeyStaysPersonalAndCrossTenantIsRejected() throws Exception {
        Founder founder = registerFounder();
        String memberToken = seedMemberAndLogin(founder);

        String linkOfOwner = createLink(founder.token(), "https://example.com/owner-" + System.currentTimeMillis());
        String linkOfMember = createLink(memberToken, "https://example.com/member-" + System.currentTimeMillis());

        String tenantKey = createKeyPlaintext(founder.token(), "tenant-wide", "TENANT");

        assertEquals(200, getWithHeader("/api/links/" + linkOfOwner, null, API_KEY_HEADER, tenantKey)
                .getStatusCode().value(), "TENANT key must read the owner's link");
        assertEquals(200, getWithHeader("/api/links/" + linkOfMember, null, API_KEY_HEADER, tenantKey)
                .getStatusCode().value(), "TENANT key must read the member's link (same tenant)");

        ResponseEntity<String> list = getWithHeader("/api/links?page=1&size=100", null, API_KEY_HEADER, tenantKey);
        assertEquals(200, list.getStatusCode().value(), list.getBody());
        var records = body(list).path("data").path("records");
        boolean sawOwner = false;
        boolean sawMember = false;
        for (int i = 0; i < records.size(); i++) {
            sawOwner |= linkOfOwner.equals(records.get(i).path("code").asText());
            sawMember |= linkOfMember.equals(records.get(i).path("code").asText());
        }
        assertTrue(sawOwner && sawMember, "TENANT key list must span the tenant: " + list.getBody());

        String memberUserKey = createKeyPlaintext(memberToken, "member-personal", null);
        assertEquals(404, getWithHeader("/api/links/" + linkOfOwner, null, API_KEY_HEADER, memberUserKey)
                .getStatusCode().value(), "USER-scope key must not cross users, even in the same tenant");
        assertEquals(200, getWithHeader("/api/links/" + linkOfMember, null, API_KEY_HEADER, memberUserKey)
                .getStatusCode().value());

        assertEquals(404, get("/api/links/" + linkOfMember, founder.token()).getStatusCode().value(),
                "JWT ownership stays per-user; TENANT scope changes keys only");

        Founder outsider = registerFounder();
        String outsideLink = createLink(outsider.token(), "https://example.com/out-" + System.currentTimeMillis());
        String outsideKey = createKeyPlaintext(outsider.token(), "outside", "TENANT");
        assertEquals(404, getWithHeader("/api/links/" + outsideLink, null, API_KEY_HEADER, tenantKey)
                .getStatusCode().value(), "TENANT key must not cross tenants");
        assertEquals(404, getWithHeader("/api/links/" + linkOfOwner, null, API_KEY_HEADER, outsideKey)
                .getStatusCode().value(), "foreign TENANT key must not read our tenant");
    }

    @Test
    void tenantKeyActsAsItsCreatorAndNeverTouchesAdminSurfaces() throws Exception {
        Founder founder = registerFounder();
        String tenantKey = createKeyPlaintext(founder.token(), "actor", "TENANT");

        String target = "https://example.com/by-key-" + System.currentTimeMillis();
        ResponseEntity<String> create = postJsonWithHeader("/api/links",
                om.writeValueAsString(Map.of("originalUrl", target)), null, API_KEY_HEADER, tenantKey);
        assertEquals(200, create.getStatusCode().value(), create.getBody());
        String code = body(create).path("data").path("code").asText();

        Long createdBy = jdbcTemplate.queryForObject(
                "SELECT created_by FROM lf_short_link WHERE code = ?", Long.class, code);
        assertEquals(founder.userId(), createdBy, "D-C1-15: tenant key creates as its own creator");

        assertEquals(403, getWithHeader("/api/keys", null, API_KEY_HEADER, tenantKey).getStatusCode().value(),
                "TENANT key is still an API key: /api/keys stays forbidden (D-Bw2-4)");
        ResponseEntity<String> hook = postJsonWithHeader("/api/webhooks/endpoint",
                om.writeValueAsString(Map.of("url", "https://example.com/hook")), null, API_KEY_HEADER, tenantKey);
        assertEquals(403, hook.getStatusCode().value(), "TENANT key must not re-point webhooks (D-Bw2-4)");

        ResponseEntity<String> csv = getWithHeader("/api/links/export", null, API_KEY_HEADER, tenantKey);
        assertEquals(200, csv.getStatusCode().value(), csv.getBody());
        assertTrue(csv.getBody().contains(code), "tenant-wide CSV export must contain the tenant's link");
    }

    @Test
    void keyViewExposesScopeCreatorLastUsedAndRevocationIsImmediate() throws Exception {
        Founder founder = registerFounder();
        ResponseEntity<String> created = createKey(founder.token(), "auditable", "TENANT");
        long keyId = body(created).path("data").path("id").asLong();
        String plaintext = body(created).path("data").path("key").asText();

        var rows = body(get("/api/keys", founder.token())).path("data");
        var row = (com.fasterxml.jackson.databind.JsonNode) null;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).path("id").asLong() == keyId) {
                row = rows.get(i);
                break;
            }
        }
        assertTrue(row != null, "created key missing from list: " + rows);
        assertEquals("TENANT", row.path("scope").asText(), "list must expose scope: " + row);
        assertEquals(founder.userId(), row.path("createdBy").asLong(), "list must expose createdBy: " + row);

        assertEquals(200, getWithHeader("/api/links?page=1&size=10", null, API_KEY_HEADER, plaintext)
                .getStatusCode().value());
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            var listed = body(get("/api/keys", founder.token())).path("data");
            for (int i = 0; i < listed.size(); i++) {
                if (listed.get(i).path("id").asLong() == keyId) {
                    return listed.get(i).hasNonNull("lastUsedAt");
                }
            }
            return false;
        });

        assertEquals(200, delete("/api/keys/" + keyId, founder.token()).getStatusCode().value());
        assertEquals(401, getWithHeader("/api/links?page=1&size=10", null, API_KEY_HEADER, plaintext)
                .getStatusCode().value(), "revocation must fail the very next request");
    }
}

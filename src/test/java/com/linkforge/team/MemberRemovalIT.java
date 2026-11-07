package com.linkforge.team;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Batch 2b member removal (plan 5.1, 3.3): removal is logical and total for
 * credentials — the member's access dies on the NEXT request, its refresh is
 * revoked, its API keys die on their next request (owner recheck against the
 * logically deleted row), it disappears from the member list — while the
 * link it created keeps redirecting, because links are tenant assets, not
 * personal property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MemberRemovalIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";
    private static final String API_KEY_HEADER = "X-API-Key";

    @Test
    void removalKillsEveryCredentialButKeepsTenantAssets() throws Exception {
        String tenantName = unique("rm");
        ResponseEntity<String> reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", unique("owner"), "password", PASSWORD)), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        String founderToken = body(reg).path("data").path("token").asText();

        // member joins through an invitation and signs in with the accept response
        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), founderToken);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String rawToken = body(created).path("data").path("token").asText();
        String memberName = unique("member");
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", rawToken, "username", memberName, "password", PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        var memberData = body(accepted).path("data");
        long memberId = memberData.path("userId").asLong();
        String memberAccess = memberData.path("token").asText();
        String memberRefresh = memberData.path("refreshToken").asText();

        // the member builds tenant assets: a link and an API key
        String code = createLink(memberAccess, "https://example.com/removed-member-link");
        ResponseEntity<String> keyResp = postJson("/api/keys",
                om.writeValueAsString(Map.of("name", "member-key")), memberAccess);
        assertEquals(200, keyResp.getStatusCode().value(), keyResp.getBody());
        String apiKey = body(keyResp).path("data").path("key").asText();

        // everything works before the removal
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        assertEquals(200, getWithHeader(LINKS, null, API_KEY_HEADER, apiKey).getStatusCode().value());

        ResponseEntity<String> removed = delete("/api/tenant/members/" + memberId, founderToken);
        assertEquals(200, removed.getStatusCode().value(), removed.getBody());

        // every credential dies on its next request — no expiry window involved
        assertEquals(401, get(LINKS, memberAccess).getStatusCode().value(),
                "access token of a removed member");
        assertEquals(401, postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", memberRefresh)), null)
                .getStatusCode().value(), "refresh token of a removed member");
        assertEquals(401, getWithHeader(LINKS, null, API_KEY_HEADER, apiKey).getStatusCode().value(),
                "API key of a removed member");
        assertEquals(401, postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", memberName, "password", PASSWORD)), null)
                .getStatusCode().value(), "a removed member cannot log back in");

        // the link is a tenant asset: untouched by its creator's removal
        ResponseEntity<String> redirect = get("/r/" + code, null);
        assertEquals(302, redirect.getStatusCode().value(),
                "the removed member's link still redirects: " + redirect.getBody());

        // and the member is gone from the list
        ResponseEntity<String> members = get("/api/tenant/members", founderToken);
        assertEquals(200, members.getStatusCode().value(), members.getBody());
        for (var row : body(members).path("data")) {
            assertFalse(row.path("id").asLong() == memberId,
                    "removed member must not be listed: " + members.getBody());
        }
    }
}

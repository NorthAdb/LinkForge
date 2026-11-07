package com.linkforge.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Batch 2b guards (plan 5.1, 3.3): a tenant can never be left ADMIN-less by
 * the member surface — the last active ADMIN cannot be demoted, disabled or
 * removed — and self-removal is always refused, even when another ADMIN
 * exists. Both rules are evaluated inside the write transaction under the
 * tenant row lock; these tests pin the sequential outcomes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LastAdminGuardIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    private record Founder(String token, long userId) {
    }

    private Founder registerFounder() throws Exception {
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("last"), "username", unique("owner"), "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Founder(data.path("token").asText(), data.path("userId").asLong());
    }

    private long joinMember(Founder founder) throws Exception {
        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), founder.token());
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String rawToken = body(created).path("data").path("token").asText();
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of("token", rawToken,
                        "username", unique("member"), "password", PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        return body(accepted).path("data").path("userId").asLong();
    }

    private JsonNode memberRow(String adminToken, long userId) throws Exception {
        ResponseEntity<String> members = get("/api/tenant/members", adminToken);
        assertEquals(200, members.getStatusCode().value(), members.getBody());
        for (var row : body(members).path("data")) {
            if (row.path("id").asLong() == userId) {
                return row;
            }
        }
        throw new IllegalStateException("member " + userId + " not listed: " + members.getBody());
    }

    @Test
    void soleAdminCannotBeDemotedDisabledOrRemoved() throws Exception {
        Founder founder = registerFounder();

        ResponseEntity<String> demote = patchJson("/api/tenant/members/" + founder.userId(),
                om.writeValueAsString(Map.of("role", "MEMBER")), founder.token());
        assertEquals(400, demote.getStatusCode().value(), demote.getBody());

        ResponseEntity<String> disable = patchJson("/api/tenant/members/" + founder.userId(),
                om.writeValueAsString(Map.of("status", 0)), founder.token());
        assertEquals(400, disable.getStatusCode().value(), disable.getBody());

        ResponseEntity<String> remove = delete("/api/tenant/members/" + founder.userId(), founder.token());
        assertEquals(400, remove.getStatusCode().value(), remove.getBody());

        // nothing moved: still the active ADMIN, and the session still works
        JsonNode row = memberRow(founder.token(), founder.userId());
        assertEquals("ADMIN", row.path("role").asText());
        assertEquals(1, row.path("status").asInt());
        assertEquals(200, get(LINKS, founder.token()).getStatusCode().value());
    }

    @Test
    void removeSelfStaysForbiddenWithSecondAdminButDemotionOpensUp() throws Exception {
        Founder founder = registerFounder();
        long memberId = joinMember(founder);

        ResponseEntity<String> promoted = patchJson("/api/tenant/members/" + memberId,
                om.writeValueAsString(Map.of("role", "ADMIN")), founder.token());
        assertEquals(200, promoted.getStatusCode().value(), promoted.getBody());
        assertEquals("ADMIN", body(promoted).path("data").path("role").asText());

        // a second ADMIN now exists, yet removing YOURSELF is still refused
        ResponseEntity<String> selfRemove = delete("/api/tenant/members/" + founder.userId(), founder.token());
        assertEquals(400, selfRemove.getStatusCode().value(), selfRemove.getBody());

        // With two active ADMINs the last-admin guard no longer applies. (The
        // plan forbids only remove-self outright, so the founder demoting
        // THEMSELF is the sharpest form of this assertion.)
        ResponseEntity<String> demoted = patchJson("/api/tenant/members/" + founder.userId(),
                om.writeValueAsString(Map.of("role", "MEMBER")), founder.token());
        assertEquals(200, demoted.getStatusCode().value(),
                "with a second ADMIN the founder may be demoted: " + demoted.getBody());
        assertEquals("MEMBER", body(demoted).path("data").path("role").asText());
    }
}

package com.linkforge.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.entity.TenantInvitation;
import com.linkforge.user.mapper.TenantInvitationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 2b invitations (plan 5.1): an ADMIN mints a single-use bearer
 * invitation; accepting it creates the account inside the inviter's tenant
 * with the invited role and returns a full login response. Every failure —
 * unknown, expired, revoked, already used — is the same 401 with the same
 * message and creates nothing (anti-enumeration, plan 5.2). A duplicate
 * username is the one distinguishable failure (409) and does NOT consume
 * the invitation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InvitationFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    @Autowired
    private TenantInvitationMapper invitationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Founder(String token, long tenantId, String tenantName) {
    }

    private Founder registerFounder() throws Exception {
        String tenantName = unique("inv");
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", unique("owner"), "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Founder(data.path("token").asText(), data.path("tenantId").asLong(), tenantName);
    }

    private JsonNode createInvitation(Founder founder, String role) throws Exception {
        ResponseEntity<String> resp = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", role, "noteName", "new hire")),
                founder.token());
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data");
    }

    private ResponseEntity<String> accept(String token, String username, String password) throws Exception {
        return postJson("/api/auth/accept-invitation", om.writeValueAsString(Map.of(
                "token", token, "username", username, "password", password)), null);
    }

    private int userCount(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    @Test
    void acceptCreatesAccountInTenantWithInvitedRoleAndUsableSession() throws Exception {
        Founder founder = registerFounder();
        JsonNode invitation = createInvitation(founder, "MEMBER");
        String rawToken = invitation.path("token").asText();
        assertFalse(rawToken.isBlank(), "the raw token appears exactly once: in this response");
        assertEquals("/?invite=" + rawToken, invitation.path("invitationLink").asText());
        assertEquals("MEMBER", invitation.path("invitedRole").asText());
        assertFalse(invitation.path("expiresAt").isMissingNode(), "expiry is part of the contract");

        String username = unique("invitee");
        ResponseEntity<String> accepted = accept(rawToken, username, PASSWORD);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        var data = body(accepted).path("data");
        assertEquals(founder.tenantId(), data.path("tenantId").asLong(),
                "the account lands in the inviter's tenant");
        assertEquals("MEMBER", data.path("tenantRole").asText());
        assertFalse(data.path("refreshToken").asText().isBlank(),
                "acceptance returns a full login response, refresh included");

        ResponseEntity<String> links = get(LINKS, data.path("token").asText());
        assertEquals(200, links.getStatusCode().value(),
                "the freshly accepted session can call protected endpoints: " + links.getBody());

        // the pending list never carries token material
        JsonNode second = createInvitation(founder, "MEMBER");
        ResponseEntity<String> pending = get("/api/tenant/invitations", founder.token());
        assertEquals(200, pending.getStatusCode().value(), pending.getBody());
        assertFalse(pending.getBody().contains(second.path("token").asText()),
                "pending list must not leak an outstanding token");
        assertFalse(pending.getBody().contains(rawToken),
                "the spent invitation is neither listed nor leaked");
    }

    @Test
    void acceptAdminInvitationGrantsAdmin() throws Exception {
        Founder founder = registerFounder();
        JsonNode invitation = createInvitation(founder, "ADMIN");
        ResponseEntity<String> accepted = accept(
                invitation.path("token").asText(), unique("coadmin"), PASSWORD);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        assertEquals("ADMIN", body(accepted).path("data").path("tenantRole").asText());

        ResponseEntity<String> members = get("/api/tenant/members",
                body(accepted).path("data").path("token").asText());
        assertEquals(200, members.getStatusCode().value(),
                "an invited ADMIN can immediately use the admin surface: " + members.getBody());
    }

    @Test
    void invalidInvitationsAllFailWithTheSame401AndCreateNothing() throws Exception {
        Founder founder = registerFounder();

        // unknown token
        String unknownUser = unique("ghost");
        ResponseEntity<String> unknown = accept("never-issued-token", unknownUser, PASSWORD);
        String expectedMessage = assertUniform401(unknown);
        assertEquals(0, userCount(unknownUser));

        // expired: backdate the row instead of waiting (plan A8 — no sleeps)
        JsonNode expiring = createInvitation(founder, "MEMBER");
        TenantInvitation row = invitationMapper.selectById(expiring.path("id").asLong());
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        invitationMapper.updateById(row);
        String expiredUser = unique("late");
        ResponseEntity<String> expired = accept(expiring.path("token").asText(), expiredUser, PASSWORD);
        assertEquals(expectedMessage, assertUniform401(expired));
        assertEquals(0, userCount(expiredUser));

        // revoked by the creator
        JsonNode revocable = createInvitation(founder, "MEMBER");
        ResponseEntity<String> revoked = delete(
                "/api/tenant/invitations/" + revocable.path("id").asLong(), founder.token());
        assertEquals(200, revoked.getStatusCode().value(), revoked.getBody());
        String revokedUser = unique("revoked");
        ResponseEntity<String> revokedAccept = accept(
                revocable.path("token").asText(), revokedUser, PASSWORD);
        assertEquals(expectedMessage, assertUniform401(revokedAccept));
        assertEquals(0, userCount(revokedUser));

        // already used: single-use means the second accept is the same 401
        JsonNode single = createInvitation(founder, "MEMBER");
        assertEquals(200, accept(single.path("token").asText(), unique("first"), PASSWORD)
                .getStatusCode().value());
        String secondUser = unique("second");
        ResponseEntity<String> reused = accept(single.path("token").asText(), secondUser, PASSWORD);
        assertEquals(expectedMessage, assertUniform401(reused));
        assertEquals(0, userCount(secondUser));
    }

    private String assertUniform401(ResponseEntity<String> resp) throws Exception {
        assertEquals(401, resp.getStatusCode().value(), resp.getBody());
        assertEquals(401, body(resp).path("code").asInt());
        return body(resp).path("message").asText();
    }

    @Test
    void duplicateUsernameIs409AndInvitationStaysUsable() throws Exception {
        Founder founder = registerFounder();
        String takenName = unique("taken");
        JsonNode first = createInvitation(founder, "MEMBER");
        assertEquals(200, accept(first.path("token").asText(), takenName, PASSWORD)
                .getStatusCode().value());

        JsonNode second = createInvitation(founder, "MEMBER");
        ResponseEntity<String> conflict = accept(second.path("token").asText(), takenName, PASSWORD);
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        Integer inTenant = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_user WHERE tenant_id = ? AND username = ?",
                Integer.class, founder.tenantId(), takenName);
        assertEquals(1, inTenant, "exactly one account carries the contested name");

        // the 409 did not consume the invitation: a different name goes through
        ResponseEntity<String> retry = accept(second.path("token").asText(), unique("fresh"), PASSWORD);
        assertEquals(200, retry.getStatusCode().value(),
                "the invitee can retry under another name: " + retry.getBody());
    }

    @Test
    void shortPasswordIs400AndInvitationStaysUsable() throws Exception {
        Founder founder = registerFounder();
        JsonNode invitation = createInvitation(founder, "MEMBER");
        String attemptedName = unique("shorty");
        ResponseEntity<String> tooShort = accept(invitation.path("token").asText(), attemptedName, "short");
        assertEquals(400, tooShort.getStatusCode().value(), tooShort.getBody());
        assertEquals(0, userCount(attemptedName), "a rejected password must not create the account");

        ResponseEntity<String> proper = accept(invitation.path("token").asText(), unique("proper"), PASSWORD);
        assertEquals(200, proper.getStatusCode().value(), proper.getBody());
        assertTrue(userCount(body(proper).path("data").path("username").asText()) == 1);
    }
}

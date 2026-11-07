package com.linkforge.team;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 2b gates (plan 5.1): the team surface is ADMIN-only (a MEMBER caller
 * is a 403 on every verb) and strictly tenant-scoped (another tenant's member
 * or invitation id is a 404 — existence does not leak across tenants).
 *
 * <p>The legacy-role case pins the role formalization: tenant_role has been
 * NOT NULL DEFAULT 'MEMBER' since V8, so a literal NULL row cannot even be
 * constructed anymore; what CAN be pinned is the observable contract — an
 * account inserted without any role written (the mapper omits the null
 * field, the column default lands) shows up as MEMBER in the list, cannot
 * use the admin surface, and no lf_user row anywhere carries a NULL role
 * after the V12 backfill ran.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MemberAdminGuardIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";

    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Founder(String token, long userId, long tenantId, String tenantName) {
    }

    private record Member(String token, long userId, String username) {
    }

    private Founder registerFounder() throws Exception {
        String tenantName = unique("guard");
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", unique("owner"), "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Founder(data.path("token").asText(), data.path("userId").asLong(),
                data.path("tenantId").asLong(), tenantName);
    }

    /** Joins a MEMBER to the founder's tenant through the real invitation flow. */
    private Member joinMember(Founder founder) throws Exception {
        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), founder.token());
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String rawToken = body(created).path("data").path("token").asText();
        String username = unique("member");
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", rawToken, "username", username, "password", PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        var data = body(accepted).path("data");
        return new Member(data.path("token").asText(), data.path("userId").asLong(), username);
    }

    @Test
    void memberCallerIsForbiddenOnTheWholeTeamSurface() throws Exception {
        Founder founder = registerFounder();
        Member member = joinMember(founder);

        assertEquals(403, postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), member.token())
                .getStatusCode().value(), "MEMBER creating an invitation");
        assertEquals(403, get("/api/tenant/invitations", member.token()).getStatusCode().value(),
                "MEMBER listing invitations");
        assertEquals(403, get("/api/tenant/members", member.token()).getStatusCode().value(),
                "MEMBER listing members");
        assertEquals(403, patchJson("/api/tenant/members/" + founder.userId(),
                om.writeValueAsString(Map.of("role", "MEMBER")), member.token())
                .getStatusCode().value(), "MEMBER patching a member");
        assertEquals(403, delete("/api/tenant/members/" + founder.userId(), member.token())
                .getStatusCode().value(), "MEMBER removing a member");
        assertEquals(403, postJson("/api/tenant/members/" + founder.userId() + "/reset-password",
                "{}", member.token()).getStatusCode().value(), "MEMBER resetting a password");
    }

    @Test
    void foreignTenantIdsAre404NotHints() throws Exception {
        Founder founderA = registerFounder();
        Founder founderB = registerFounder();

        assertEquals(404, patchJson("/api/tenant/members/" + founderB.userId(),
                om.writeValueAsString(Map.of("role", "MEMBER")), founderA.token())
                .getStatusCode().value(), "patching another tenant's founder");
        assertEquals(404, delete("/api/tenant/members/" + founderB.userId(), founderA.token())
                .getStatusCode().value(), "removing another tenant's founder");
        assertEquals(404, postJson("/api/tenant/members/" + founderB.userId() + "/reset-password",
                "{}", founderA.token()).getStatusCode().value(),
                "resetting another tenant's founder");

        ResponseEntity<String> bInvitation = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), founderB.token());
        assertEquals(200, bInvitation.getStatusCode().value(), bInvitation.getBody());
        long bInvitationId = body(bInvitation).path("data").path("id").asLong();
        assertEquals(404, delete("/api/tenant/invitations/" + bInvitationId, founderA.token())
                .getStatusCode().value(), "revoking another tenant's invitation");
    }

    @Test
    void accountWithoutWrittenRoleReadsAsMemberAndCannotAdmin() throws Exception {
        Founder founder = registerFounder();

        // inserted with no role at all (TenantApiKeyIT's seeding posture): the
        // mapper omits the null field and the column default lands — the row
        // must behave as a MEMBER everywhere it is read.
        UserAccount legacy = new UserAccount();
        legacy.setTenantId(founder.tenantId());
        legacy.setUsername(unique("legacy"));
        legacy.setPasswordHash(passwordEncoder.encode(PASSWORD));
        legacy.setStatus(1);
        legacy.setDeleted(0);
        legacy.setCreatedAt(LocalDateTime.now());
        legacy.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.insert(legacy);

        ResponseEntity<String> members = get("/api/tenant/members", founder.token());
        assertEquals(200, members.getStatusCode().value(), members.getBody());
        boolean listedAsMember = false;
        for (var row : body(members).path("data")) {
            if (row.path("id").asLong() == legacy.getId()) {
                listedAsMember = "MEMBER".equals(row.path("role").asText());
            }
        }
        assertTrue(listedAsMember, "the role-less account must list as MEMBER: " + members.getBody());

        ResponseEntity<String> login = postJson("/api/auth/login", om.writeValueAsString(Map.of(
                "tenantName", founder.tenantName(), "username", legacy.getUsername(),
                "password", PASSWORD)), null);
        assertEquals(200, login.getStatusCode().value(), login.getBody());
        String legacyToken = body(login).path("data").path("token").asText();
        assertEquals(403, postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), legacyToken)
                .getStatusCode().value(), "a role-less account is no ADMIN");

        Integer nullRoles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_user WHERE tenant_role IS NULL", Integer.class);
        assertEquals(0, nullRoles, "after V12 no stored role may be NULL (backfill guarantee)");
    }
}

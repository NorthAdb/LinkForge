package com.linkforge.team;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.common.exception.BizException;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.user.dto.UpdateMemberRequest;
import com.linkforge.user.service.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M2 contract: the last-ADMIN guard is evaluated under the tenant row
 * lock with locking current reads, so concurrent member writes can never
 * leave a tenant with zero active ADMINs. These tests replace the audit
 * probe {@code concurrentSelfDemotionsLeaveNoAdmin} (which asserted the BAD
 * behavior) with the correct contract. Calls go to the real TeamService
 * against real MySQL; a CyclicBarrier releases both writers together, and
 * outcomes are verified in the database, not just by "no exception flew".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LastAdminConcurrencyIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";

    @Autowired
    private TeamService teamService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Actor(long userId, long tenantId, String username, String token) {

        AuthUser principal() {
            return new AuthUser(userId, tenantId, username, null);
        }
    }

    private Actor registerFounder() throws Exception {
        String tenantName = unique("ladm");
        String username = unique("owner");
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", username, "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Actor(data.path("userId").asLong(), data.path("tenantId").asLong(),
                username, data.path("token").asText());
    }

    private Actor joinAsAdmin(Actor founder) throws Exception {
        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "ADMIN")), founder.token());
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String username = unique("admin");
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", body(created).path("data").path("token").asText(),
                        "username", username, "password", PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        var data = body(accepted).path("data");
        return new Actor(data.path("userId").asLong(), data.path("tenantId").asLong(),
                username, data.path("token").asText());
    }

    private long activeAdmins(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_user WHERE tenant_id = ? AND tenant_role = 'ADMIN' "
                        + "AND status = 1 AND deleted = 0",
                Long.class, tenantId);
        return count == null ? -1 : count;
    }

    @FunctionalInterface
    private interface MemberCall {
        void run() throws Exception;
    }

    /** Runs one member call as the given actor on the calling thread. */
    private void asActor(Actor actor, MemberCall call) throws Exception {
        UserContext.set(actor.principal());
        try {
            call.run();
        } finally {
            UserContext.clear();
        }
    }

    /**
     * Releases both calls on a barrier and records each outcome: the string
     * "OK" for a success, the thrown Throwable otherwise.
     */
    private List<Object> race(MemberCall first, MemberCall second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        Runnable wrapFirst = () -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
                first.run();
                outcomes.add("OK");
            } catch (Throwable ex) {
                outcomes.add(ex);
            }
        };
        Runnable wrapSecond = () -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
                second.run();
                outcomes.add("OK");
            } catch (Throwable ex) {
                outcomes.add(ex);
            }
        };
        Thread t1 = new Thread(wrapFirst);
        Thread t2 = new Thread(wrapSecond);
        t1.start();
        t2.start();
        t1.join(TimeUnit.SECONDS.toMillis(60));
        t2.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(t1.isAlive() || t2.isAlive(), "racing member calls stuck");
        assertEquals(2, outcomes.size());
        return outcomes;
    }

    /** The M2 invariant for every race: exactly one winner, the loser refused
     * with a BizException (never an unexpected error), and the tenant keeps
     * exactly one active ADMIN in the database. */
    private void assertOneWinnerOneRefusal(List<Object> outcomes, long tenantId) {
        long ok = outcomes.stream().filter("OK"::equals).count();
        long refused = outcomes.stream().filter(BizException.class::isInstance).count();
        assertEquals(1, ok, "exactly one call may succeed, outcomes: " + outcomes);
        assertEquals(1, refused,
                "the loser must be refused with a BizException, outcomes: " + outcomes);
        assertEquals(1, activeAdmins(tenantId),
                "the tenant must keep exactly one active ADMIN");
    }

    private static UpdateMemberRequest role(String role) {
        UpdateMemberRequest request = new UpdateMemberRequest();
        request.setRole(role);
        return request;
    }

    private static UpdateMemberRequest status(int status) {
        UpdateMemberRequest request = new UpdateMemberRequest();
        request.setStatus(status);
        return request;
    }

    @Test
    void concurrentSelfDemotionsKeepOneAdmin() throws Exception {
        Actor a = registerFounder();
        Actor b = joinAsAdmin(a);
        assertEquals(2, activeAdmins(a.tenantId()));

        List<Object> outcomes = race(
                () -> asActor(a, () -> teamService.updateMember(a.userId(), role("MEMBER"))),
                () -> asActor(b, () -> teamService.updateMember(b.userId(), role("MEMBER"))));

        assertOneWinnerOneRefusal(outcomes, a.tenantId());
        BizException refusal = outcomes.stream()
                .filter(BizException.class::isInstance)
                .map(BizException.class::cast)
                .findFirst().orElseThrow();
        assertEquals(400, refusal.getHttpStatus(),
                "the loser is refused by the last-ADMIN guard itself");
    }

    @Test
    void crossDemotionDisableAndDeleteKeepOneAdmin() throws Exception {
        // demote A vs demote B (self-demotion pair)
        {
            Actor a = registerFounder();
            Actor b = joinAsAdmin(a);
            List<Object> outcomes = race(
                    () -> asActor(a, () -> teamService.updateMember(a.userId(), role("MEMBER"))),
                    () -> asActor(b, () -> teamService.updateMember(b.userId(), role("MEMBER"))));
            assertOneWinnerOneRefusal(outcomes, a.tenantId());
        }
        // demote A vs disable B
        {
            Actor a = registerFounder();
            Actor b = joinAsAdmin(a);
            List<Object> outcomes = race(
                    () -> asActor(a, () -> teamService.updateMember(a.userId(), role("MEMBER"))),
                    () -> asActor(b, () -> teamService.updateMember(b.userId(), status(0))));
            assertOneWinnerOneRefusal(outcomes, a.tenantId());
        }
        // disable A (by B) vs remove B (by A): whichever lands second finds its
        // caller no longer an active ADMIN under the lock and is refused
        {
            Actor a = registerFounder();
            Actor b = joinAsAdmin(a);
            List<Object> outcomes = race(
                    () -> asActor(b, () -> teamService.updateMember(a.userId(), status(0))),
                    () -> asActor(a, () -> teamService.removeMember(b.userId())));
            assertOneWinnerOneRefusal(outcomes, a.tenantId());
        }
    }

    @Test
    void staleCallerIsReauthorizedUnderLock() throws Exception {
        Actor a = registerFounder();
        Actor b = joinAsAdmin(a);

        // A demotes B; the operation completes and commits.
        asActor(a, () -> teamService.updateMember(b.userId(), role("MEMBER")));
        assertEquals("MEMBER", jdbcTemplate.queryForObject(
                "SELECT tenant_role FROM lf_user WHERE id = ?", String.class, b.userId()));

        // B's principal was minted while B was an ADMIN (a queued request, a
        // stale token): authorization is re-evaluated at execution time under
        // the tenant lock, from the current row — B is refused, A untouched.
        BizException refusal = null;
        try {
            asActor(b, () -> teamService.updateMember(a.userId(), role("MEMBER")));
        } catch (BizException ex) {
            refusal = ex;
        }
        assertTrue(refusal != null, "the demoted caller's queued call must be refused");
        assertEquals(403, refusal.getHttpStatus());
        assertEquals("ADMIN", jdbcTemplate.queryForObject(
                "SELECT tenant_role FROM lf_user WHERE id = ?", String.class, a.userId()),
                "the refused call must not change its target");
        assertEquals(1, activeAdmins(a.tenantId()));
    }
}

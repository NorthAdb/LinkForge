package com.linkforge.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.auth.mapper.RefreshTokenMapper;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.JwtService;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M1 contract: the credential lifecycle is serialized on the user row,
 * so a revocation can never be overtaken by an in-flight rotation. These
 * tests replace the audit probe {@code rotationRacingLogoutAllCreatesSurvivingRefresh}
 * (which asserted the BAD behavior) with the correct contract: the rotation
 * is paused mid-transaction — after the rotate, before the successor insert
 * commits — by overriding the {@code SessionService.insertSuccessor} seam,
 * and the revocation runs concurrently. Interleavings are controlled by
 * latches, never sleeps. The seam configuration lives only in this class's
 * context; every other IT keeps the stock SessionService.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionRevocationConcurrencyIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    /** The latching SessionService, scoped to this test class only. */
    @TestConfiguration
    static class SeamConfig {

        @Bean
        @Primary
        SessionService latchingSessionService(RefreshTokenMapper refreshTokenMapper,
                                              UserAccountMapper userAccountMapper,
                                              TenantMapper tenantMapper,
                                              JwtService jwtService,
                                              PasswordEncoder passwordEncoder,
                                              RedisRateLimiter rateLimiter,
                                              LinkForgeProperties properties,
                                              MeterRegistry meterRegistry,
                                              PlatformTransactionManager transactionManager) {
            return new LatchingSessionService(refreshTokenMapper, userAccountMapper, tenantMapper,
                    jwtService, passwordEncoder, rateLimiter, properties, meterRegistry,
                    transactionManager);
        }
    }

    static class LatchingSessionService extends SessionService {

        /** When set, insertSuccessor counts {@code insertEntered} down and
         * blocks on {@code insertRelease} — inside the rotation transaction,
         * holding the user row lock, after the rotate UPDATE. */
        static volatile CountDownLatch insertEntered;
        static volatile CountDownLatch insertRelease;
        /** When true, the next insertSuccessor throws once (then self-clears). */
        static volatile boolean failNextInsert;

        LatchingSessionService(RefreshTokenMapper refreshTokenMapper,
                               UserAccountMapper userAccountMapper,
                               TenantMapper tenantMapper,
                               JwtService jwtService,
                               PasswordEncoder passwordEncoder,
                               RedisRateLimiter rateLimiter,
                               LinkForgeProperties properties,
                               MeterRegistry meterRegistry,
                               PlatformTransactionManager transactionManager) {
            super(refreshTokenMapper, userAccountMapper, tenantMapper, jwtService,
                    passwordEncoder, rateLimiter, properties, meterRegistry, transactionManager);
        }

        @Override
        protected void insertSuccessor(RefreshToken row) {
            if (failNextInsert) {
                failNextInsert = false;
                throw new IllegalStateException("injected successor insert failure");
            }
            CountDownLatch entered = insertEntered;
            if (entered != null) {
                entered.countDown();
                try {
                    if (!insertRelease.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test latch was never released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            super.insertSuccessor(row);
        }

        static void resetSeam() {
            insertEntered = null;
            insertRelease = null;
            failNextInsert = false;
        }
    }

    @Autowired
    private SessionService sessionService;
    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void resetSeam() {
        LatchingSessionService.resetSeam();
    }

    private record Session(String access, String refresh, long userId) {
    }

    private Session registerSession() throws Exception {
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("rev"), "username", unique("u"), "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Session(data.path("token").asText(), data.path("refreshToken").asText(),
                data.path("userId").asLong());
    }

    private ResponseEntity<String> refreshCall(String rawRefresh) throws Exception {
        return postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", rawRefresh)), null);
    }

    private RefreshToken rowOf(String rawRefresh) {
        return refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, sha256Hex(rawRefresh)));
    }

    private long liveRefreshRows(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, userId);
        return count == null ? -1 : count;
    }

    static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Runs a refresh over HTTP in a thread, paused inside the rotation
     * transaction; returns the thread once the pause point is reached.
     */
    private Thread startPausedRefresh(String rawRefresh,
                                      AtomicReference<ResponseEntity<String>> out) {
        LatchingSessionService.insertEntered = new CountDownLatch(1);
        LatchingSessionService.insertRelease = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                out.set(refreshCall(rawRefresh));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        thread.start();
        try {
            assertTrue(LatchingSessionService.insertEntered.await(30, TimeUnit.SECONDS),
                    "the refresh must reach the successor insert (holding the user lock)");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return thread;
    }

    @Test
    void logoutAllInvalidatesInFlightRefresh() throws Exception {
        Session session = registerSession();

        AtomicReference<ResponseEntity<String>> refreshOut = new AtomicReference<>();
        Thread refreshThread = startPausedRefresh(session.refresh(), refreshOut);

        // logout-all starts while the rotation is paused: it must queue on the
        // user row lock, then revoke the successor the rotation just minted.
        AtomicReference<ResponseEntity<String>> logoutOut = new AtomicReference<>();
        Thread logoutThread = new Thread(() -> {
            try {
                logoutOut.set(postJson("/api/auth/logout-all", "{}", session.access()));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        logoutThread.start();

        LatchingSessionService.insertRelease.countDown();
        refreshThread.join(TimeUnit.SECONDS.toMillis(60));
        logoutThread.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(refreshThread.isAlive(), "refresh thread stuck");
        assertFalse(logoutThread.isAlive(), "logout-all thread stuck");

        // both calls succeed: the rotation won the lock first and returned a
        // fresh pair, and logout-all then ended every session — including it.
        assertEquals(200, refreshOut.get().getStatusCode().value(), refreshOut.get().getBody());
        assertEquals(200, logoutOut.get().getStatusCode().value(), logoutOut.get().getBody());
        var pair = body(refreshOut.get()).path("data");
        String successorRefresh = pair.path("refreshToken").asText();
        String successorAccess = pair.path("token").asText();

        assertEquals(401, refreshCall(successorRefresh).getStatusCode().value(),
                "the in-flight successor must be revoked by the queued logout-all");
        assertEquals(401, get(LINKS, successorAccess).getStatusCode().value(),
                "the in-flight access token dies by the logout-all version bump");
        assertEquals(401, get(LINKS, session.access()).getStatusCode().value(),
                "the pre-rotation access token dies by the same bump");
        assertEquals(0, liveRefreshRows(session.userId()),
                "no live refresh row of the family may survive logout-all");
    }

    @Test
    void resetDuringInFlightRefreshLeavesNoLiveSession() throws Exception {
        String tenantName = unique("revrst");
        ResponseEntity<String> reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", tenantName, "username", unique("owner"), "password", PASSWORD)), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        String founderToken = body(reg).path("data").path("token").asText();

        ResponseEntity<String> created = postJson("/api/tenant/invitations",
                om.writeValueAsString(Map.of("invitedRole", "MEMBER")), founderToken);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        ResponseEntity<String> accepted = postJson("/api/auth/accept-invitation",
                om.writeValueAsString(Map.of(
                        "token", body(created).path("data").path("token").asText(),
                        "username", unique("member"), "password", PASSWORD)), null);
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        long memberId = body(accepted).path("data").path("userId").asLong();
        String memberRefresh = body(accepted).path("data").path("refreshToken").asText();

        AtomicReference<ResponseEntity<String>> refreshOut = new AtomicReference<>();
        Thread refreshThread = startPausedRefresh(memberRefresh, refreshOut);

        AtomicReference<ResponseEntity<String>> resetOut = new AtomicReference<>();
        Thread resetThread = new Thread(() -> {
            try {
                resetOut.set(postJson("/api/tenant/members/" + memberId + "/reset-password",
                        "{}", founderToken));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        resetThread.start();

        LatchingSessionService.insertRelease.countDown();
        refreshThread.join(TimeUnit.SECONDS.toMillis(60));
        resetThread.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(refreshThread.isAlive(), "refresh thread stuck");
        assertFalse(resetThread.isAlive(), "reset thread stuck");
        assertEquals(200, refreshOut.get().getStatusCode().value(), refreshOut.get().getBody());
        assertEquals(200, resetOut.get().getStatusCode().value(), resetOut.get().getBody());

        var pair = body(refreshOut.get()).path("data");
        assertEquals(401, refreshCall(pair.path("refreshToken").asText()).getStatusCode().value(),
                "the successor minted during the reset race must be revoked by the reset");
        assertEquals(401, get(LINKS, pair.path("token").asText()).getStatusCode().value(),
                "the successor access token dies by the reset version bump");
        assertEquals(0, liveRefreshRows(memberId),
                "no live refresh row may survive an admin reset");
        Integer mustChange = jdbcTemplate.queryForObject(
                "SELECT must_change_password FROM lf_user WHERE id = ?", Integer.class, memberId);
        assertEquals(1, mustChange, "the reset's forced-change flag stands");
    }

    @Test
    void successorInsertFailureRollsBackRotation() {
        Session session;
        try {
            session = registerSession();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        LatchingSessionService.failNextInsert = true;
        assertThrows(RuntimeException.class,
                () -> sessionService.refresh(session.refresh(), new MockHttpServletRequest()),
                "the injected insert failure must surface");

        RefreshToken old = rowOf(session.refresh());
        assertNotNull(old);
        assertNull(old.getRevokedAt(),
                "a failed successor insert must roll the rotation back: old token NOT consumed");
        assertNull(old.getReplacedById(), "no successor may be linked after a rollback");

        // the seam has self-cleared: the very same token still rotates normally
        LoginResponse retried = sessionService.refresh(session.refresh(), new MockHttpServletRequest());
        assertNotNull(retried.getRefreshToken());
        RefreshToken rotated = rowOf(session.refresh());
        assertNotNull(rotated.getRevokedAt(), "the retry performs the rotation");
        assertEquals(RefreshToken.CAUSE_ROTATED, rotated.getRevokeCause());
        assertNotNull(rotated.getReplacedById());
    }

    @Test
    void concurrentTokenVersionBumpsAreNotLost() throws Exception {
        Session session = registerSession();
        long versionBefore = jdbcTemplate.queryForObject(
                "SELECT token_version FROM lf_user WHERE id = ?", Long.class, session.userId());
        String hashBefore = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM lf_user WHERE id = ?", String.class, session.userId());
        String roleBefore = jdbcTemplate.queryForObject(
                "SELECT tenant_role FROM lf_user WHERE id = ?", String.class, session.userId());

        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable bump = () -> {
            try {
                gate.await();
                sessionService.logoutAll(session.userId());
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        };
        Thread first = new Thread(bump);
        Thread second = new Thread(bump);
        first.start();
        second.start();
        gate.countDown();
        first.join(TimeUnit.SECONDS.toMillis(60));
        second.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(first.isAlive() || second.isAlive(), "bump threads stuck");
        assertNull(failure.get(), "both logout-all calls must succeed: " + failure.get());

        long versionAfter = jdbcTemplate.queryForObject(
                "SELECT token_version FROM lf_user WHERE id = ?", Long.class, session.userId());
        assertEquals(versionBefore + 2, versionAfter,
                "two committed revocations must add exactly two — no lost increment");
        assertEquals(hashBefore, jdbcTemplate.queryForObject(
                "SELECT password_hash FROM lf_user WHERE id = ?", String.class, session.userId()),
                "the atomic increment must not overwrite the password hash");
        assertEquals(roleBefore, jdbcTemplate.queryForObject(
                "SELECT tenant_role FROM lf_user WHERE id = ?", String.class, session.userId()),
                "the atomic increment must not overwrite the role");
    }

    @Test
    void reuseKillStillEscalates() throws Exception {
        Session session = registerSession();
        long versionBefore = jdbcTemplate.queryForObject(
                "SELECT token_version FROM lf_user WHERE id = ?", Long.class, session.userId());

        ResponseEntity<String> first = refreshCall(session.refresh());
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        String liveRefresh = body(first).path("data").path("refreshToken").asText();

        // the rotated-away token's revocation is suddenly 60s old: past the grace
        jdbcTemplate.update("UPDATE lf_refresh_token SET revoked_at = ? WHERE token_hash = ?",
                LocalDateTime.now().minusSeconds(60), sha256Hex(session.refresh()));

        assertEquals(401, refreshCall(session.refresh()).getStatusCode().value(),
                "presenting a rotated token past the grace is refused");
        assertEquals(401, refreshCall(liveRefresh).getStatusCode().value(),
                "the reuse kill must revoke the whole family — and the kill must COMMIT "
                        + "even though the presenting call answers 401");
        long versionAfter = jdbcTemplate.queryForObject(
                "SELECT token_version FROM lf_user WHERE id = ?", Long.class, session.userId());
        assertTrue(versionAfter > versionBefore,
                "the reuse kill bumps token_version, killing outstanding access tokens");
        assertEquals(0, liveRefreshRows(session.userId()),
                "no live refresh row remains after a reuse kill");
    }
}

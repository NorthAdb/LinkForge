package com.linkforge.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linkforge.auth.mapper.RefreshTokenMapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.exception.RateLimitedException;
import com.linkforge.common.ratelimit.RateLimitDecision;
import com.linkforge.common.ratelimit.RateLimitKeys;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.common.util.ClientIps;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.JwtService;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.entity.Tenant;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Batch 2a session lifecycle on top of {@code lf_refresh_token}: issuance at
 * login/register, rotation with reuse detection, logout, logout-all and
 * password change. Credential verification itself stays in AuthService; this
 * service owns everything that happens to token material afterwards.
 *
 * <p>Every refresh failure answers the same 401 with the same message — unknown,
 * expired, revoked and reused tokens are indistinguishable from the outside
 * (no enumeration oracle), mirroring the login failure posture.
 *
 * <p><b>Audit M1 serialization:</b> every mint/revoke critical section runs in
 * one transaction whose first credential statement locks the user's row
 * ({@code UserAccountMapper.selectForUpdate}); the global lock order is
 * tenant row → user row → refresh rows. A rotation therefore commits
 * atomically with its successor insert (an insert failure rolls the rotation
 * back — the old token is NOT consumed), and a logout-all / password change /
 * admin reset that queues behind an in-flight refresh revokes the successor
 * the refresh just minted: once a revocation returns, no session it covered
 * can still be alive. token_version only ever moves through the atomic SQL
 * increment, never a read-modify-write of the whole row.
 */
@Service
public class SessionService {

    /** A just-rotated token re-presented within this window is a benign client
     * race (concurrent refresh / retry), not theft: 401, but the family lives. */
    private static final long REUSE_GRACE_SECONDS = 10;

    private static final String INVALID_REFRESH = "invalid refresh token";

    private final RefreshTokenMapper refreshTokenMapper;
    private final UserAccountMapper userAccountMapper;
    private final TenantMapper tenantMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RedisRateLimiter rateLimiter;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public SessionService(RefreshTokenMapper refreshTokenMapper,
                          UserAccountMapper userAccountMapper,
                          TenantMapper tenantMapper,
                          JwtService jwtService,
                          PasswordEncoder passwordEncoder,
                          RedisRateLimiter rateLimiter,
                          LinkForgeProperties properties,
                          MeterRegistry meterRegistry,
                          PlatformTransactionManager transactionManager) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.userAccountMapper = userAccountMapper;
        this.tenantMapper = tenantMapper;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Starts a NEW family for a fresh login/register and returns the raw refresh
     * token (the only place the raw value ever exists server-side). Every login
     * is its own family, so logging out one device never disturbs another.
     *
     * <p>The mint runs under the user's row lock and re-verifies the account is
     * still active: an account disabled or removed between credential
     * verification (AuthService) and this call never gets a session minted.
     */
    public String startSession(Long userId, HttpServletRequest request) {
        return tx.execute(status -> {
            UserAccount user = userAccountMapper.selectForUpdate(userId);
            if (!isActive(user)) {
                throw new BizException(401, "invalid credentials");
            }
            Minted minted = prepare(userId, UUID.randomUUID().toString(), request);
            refreshTokenMapper.insert(minted.row());
            return minted.raw();
        });
    }

    /**
     * Batch 2b: a full login response (access + a fresh refresh family) for an
     * account that was just created by accepting an invitation — the invitee
     * lands already signed in, exactly as if they had registered.
     */
    public LoginResponse issueLoginResponse(UserAccount user, HttpServletRequest request) {
        return buildResponse(user, startSession(user.getId(), request));
    }

    /**
     * Rotates a refresh token into a fresh access + refresh pair, atomically
     * under the owning user's row lock (audit M1): validate → rotate → insert
     * successor → link {@code replaced_by} commit together, and the access
     * token is built from the token_version read under that lock. A revocation
     * (logout-all, password change, admin reset/disable) either commits before
     * this transaction takes the lock — and this rotation then sees the revoked
     * row and refuses — or queues behind it and revokes the successor too.
     */
    public LoginResponse refresh(String rawToken, HttpServletRequest httpRequest) {
        checkRateLimit("refresh", httpRequest,
                properties.getRateLimit().getRefreshMax(),
                properties.getRateLimit().getRefreshWindowSeconds());
        if (rawToken == null || rawToken.isBlank()) {
            throw new BizException(401, INVALID_REFRESH);
        }
        String hash = sha256Hex(rawToken);
        // Lock-free peek: it only decides WHOSE row lock serializes this call.
        // It takes no lock and its result is never trusted — the transaction
        // re-reads the row FOR UPDATE once the user lock is held.
        RefreshToken peek = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, hash));
        if (peek == null) {
            throw new BizException(401, INVALID_REFRESH);
        }
        LoginResponse[] result = new LoginResponse[1];
        boolean[] reuseKilled = new boolean[1];
        tx.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            UserAccount user = userAccountMapper.selectForUpdate(peek.getUserId());
            RefreshToken row = refreshTokenMapper.selectByHashForUpdate(hash);
            if (row == null) {
                throw new BizException(401, INVALID_REFRESH);
            }
            if (row.getRevokedAt() != null) {
                if (isReuseKill(row, now)) {
                    // The kill must COMMIT before the 401 propagates, so it is
                    // performed here and the exception is raised only after
                    // execute() returns — throwing inside would roll it back.
                    refreshTokenMapper.revokeFamily(row.getFamilyId(),
                            RefreshToken.CAUSE_REUSE_KILL, now);
                    userAccountMapper.incrementTokenVersion(row.getUserId());
                    reuseKilled[0] = true;
                    return;
                }
                throw new BizException(401, INVALID_REFRESH);
            }
            if (!row.getExpiresAt().isAfter(now)) {
                throw new BizException(401, INVALID_REFRESH);
            }
            if (!isActive(user)) {
                throw new BizException(401, INVALID_REFRESH);
            }
            if (refreshTokenMapper.rotate(row.getId(), now) == 0) {
                // defensive: under the row lock this should be unreachable,
                // but a lost rotation is a refusal, never a second successor
                throw new BizException(401, INVALID_REFRESH);
            }
            Minted minted = prepare(user.getId(), row.getFamilyId(), httpRequest);
            insertSuccessor(minted.row());
            RefreshToken link = new RefreshToken();
            link.setId(row.getId());
            link.setReplacedById(minted.row().getId());
            refreshTokenMapper.updateById(link);
            result[0] = buildResponse(user, minted.raw());
        });
        if (reuseKilled[0]) {
            meterRegistry.counter("linkforge.refresh.reuse_kill").increment();
            throw new BizException(401, INVALID_REFRESH);
        }
        return result[0];
    }

    /**
     * Whether presenting this revoked row is the RFC 9700 theft signal:
     *
     * <ul>
     *   <li>ROTATED within the grace window — benign client race (concurrent
     *       refresh / retry), family untouched.</li>
     *   <li>ROTATED past the grace window — the token was retired by a
     *       legitimate rotation, so whoever presents it now is either a thief
     *       or a client holding a stolen copy: escalate (family kill + version
     *       bump).</li>
     *   <li>LOGOUT / ADMIN / PASSWORD / REUSE_KILL — a deliberate revocation.
     *       Re-presentation is most plausibly a stale honest client that has
     *       not learned of the revocation yet (e.g. a refresh racing the
     *       logout), and the remediation has already happened, so there is
     *       nothing to escalate: no family kill, no token_version bump.
     *       Escalating here would let a single logout log the user out on
     *       every device the moment any stale client retries — and would make
     *       "is the logged-out refresh really dead?" untestable, since the
     *       probe itself would trip the alarm. (Batch-2a amendment, kept.)</li>
     * </ul>
     */
    private boolean isReuseKill(RefreshToken row, LocalDateTime now) {
        return RefreshToken.CAUSE_ROTATED.equals(row.getRevokeCause())
                && row.getRevokedAt() != null
                && row.getRevokedAt().plusSeconds(REUSE_GRACE_SECONDS).isBefore(now);
    }

    /**
     * Revokes the presented token's family. Idempotent and quiet by design: an
     * unknown token also succeeds, so the endpoint is no validity oracle. Note
     * the asymmetry: the family's ACCESS token stays valid until its natural
     * expiry (at most the configured JWT lifetime) — only logout-all and
     * password change bump token_version and kill access tokens immediately.
     * The revocation itself takes the owner's row lock first (audit M1), so it
     * cannot interleave with an in-flight rotation of the same family.
     */
    public void logout(String rawToken, HttpServletRequest httpRequest) {
        checkRateLimit("logout", httpRequest,
                properties.getRateLimit().getLogoutMax(),
                properties.getRateLimit().getLogoutWindowSeconds());
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        RefreshToken row = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, sha256Hex(rawToken)));
        if (row != null) {
            tx.executeWithoutResult(status -> {
                userAccountMapper.selectForUpdate(row.getUserId());
                refreshTokenMapper.revokeFamily(row.getFamilyId(),
                        RefreshToken.CAUSE_LOGOUT, LocalDateTime.now());
            });
        }
    }

    /** Every session ends now: all refresh rows revoked and token_version bumped,
     * so outstanding access tokens die on their next request too. One locked
     * transaction (audit M1) — a rotation in flight either commits first and
     * its successor is revoked here, or starts after and sees revoked rows. */
    public void logoutAll(Long userId) {
        tx.executeWithoutResult(status -> {
            userAccountMapper.selectForUpdate(userId);
            refreshTokenMapper.revokeAllForUser(userId, RefreshToken.CAUSE_LOGOUT, LocalDateTime.now());
            userAccountMapper.incrementTokenVersion(userId);
        });
    }

    /**
     * Password change is a full sign-out event (batch 2 plan 3.2): the hash is
     * replaced, the forced-change flag clears, token_version bumps and every
     * refresh row is revoked — including the caller's own session, which must
     * log in again with the new password. Nothing survives a credential change.
     * Runs under the user's row lock with column-scoped writes (audit M1), so
     * it can neither lose a concurrent revocation's version bump nor overwrite
     * one with a stale entity.
     */
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        tx.executeWithoutResult(status -> {
            UserAccount user = userAccountMapper.selectForUpdate(userId);
            if (user == null || isDeleted(user)) {
                throw new BizException(401, "invalid credentials");
            }
            if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new BizException(401, "invalid credentials");
            }
            userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                    .eq(UserAccount::getId, userId)
                    .set(UserAccount::getPasswordHash, passwordEncoder.encode(newPassword))
                    .set(UserAccount::getMustChangePassword, 0)
                    .set(UserAccount::getUpdatedAt, LocalDateTime.now()));
            userAccountMapper.incrementTokenVersion(userId);
            refreshTokenMapper.revokeAllForUser(userId, RefreshToken.CAUSE_PASSWORD, LocalDateTime.now());
        });
    }

    private record Minted(String raw, RefreshToken row) {
    }

    /** Builds (but does not insert) a refresh row plus its raw token: 32 random
     * bytes, Base64URL; only the hash is ever stored. */
    private Minted prepare(Long userId, String familyId, HttpServletRequest request) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        RefreshToken row = new RefreshToken();
        row.setUserId(userId);
        row.setFamilyId(familyId);
        row.setTokenHash(sha256Hex(raw));
        row.setCreatedAt(now);
        row.setExpiresAt(now.plusDays(properties.getAuth().getRefreshExpireDays()));
        if (request != null) {
            row.setIp(ClientIps.resolve(request, properties.getRateLimit().isTrustXff()));
            String ua = request.getHeader("User-Agent");
            if (ua != null && ua.length() > 255) {
                ua = ua.substring(0, 255);
            }
            row.setUserAgent(ua);
        }
        return new Minted(raw, row);
    }

    /**
     * The successor insert of a rotation — the ONE seam tests may override to
     * pause or fail a rotation mid-transaction (audit M1 contract tests). The
     * default is the plain mapper insert, which populates {@code row.id}; an
     * override must do the same (or delegate here) on its success path. If it
     * throws, the whole rotation transaction rolls back and the presented
     * token stays un-rotated and usable.
     */
    protected void insertSuccessor(RefreshToken row) {
        refreshTokenMapper.insert(row);
    }

    private LoginResponse buildResponse(UserAccount user, String rawRefresh) {
        Tenant tenant = tenantMapper.selectById(user.getTenantId());
        String access = jwtService.createToken(user.getId(), user.getTenantId(),
                user.getUsername(), tokenVersionOf(user));
        LoginResponse response = new LoginResponse(access, user.getId(), user.getTenantId(),
                user.getUsername(), tenant != null ? tenant.getName() : null, user.getTenantRole());
        response.setRefreshToken(rawRefresh);
        response.setMustChangePassword(mustChangeOf(user));
        return response;
    }

    /** Active = present, not logically deleted, status flag 1. The locking read
     * behind selectForUpdate bypasses MyBatis-Plus logic-delete, so the deleted
     * flag is checked explicitly here. */
    private static boolean isActive(UserAccount user) {
        return user != null && !isDeleted(user)
                && user.getStatus() != null && user.getStatus() == 1;
    }

    private static boolean isDeleted(UserAccount user) {
        return user.getDeleted() != null && user.getDeleted() == 1;
    }

    private void checkRateLimit(String entry, HttpServletRequest request, int max, int windowSeconds) {
        LinkForgeProperties.RateLimit limit = properties.getRateLimit();
        RateLimitDecision decision = rateLimiter.acquire(
                RateLimitKeys.sw(limit.getKeyPrefix(),
                        entry + ":" + ClientIps.resolve(request, limit.isTrustXff())),
                max, windowSeconds);
        if (!decision.allowed()) {
            meterRegistry.counter("linkforge.ratelimit.rejected", "entry", entry).increment();
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
    }

    static int tokenVersionOf(UserAccount user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    static boolean mustChangeOf(UserAccount user) {
        return user.getMustChangePassword() != null && user.getMustChangePassword() == 1;
    }

    /** Shared with the batch-2b invitation flow, which stores invitation tokens
     * under the same hash-only discipline. */
    public static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

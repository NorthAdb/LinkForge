package com.linkforge.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
@RequiredArgsConstructor
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
    private final SecureRandom random = new SecureRandom();

    /**
     * Starts a NEW family for a fresh login/register and returns the raw refresh
     * token (the only place the raw value ever exists server-side). Every login
     * is its own family, so logging out one device never disturbs another.
     */
    public String startSession(Long userId, HttpServletRequest request) {
        return issue(userId, UUID.randomUUID().toString(), request).raw();
    }

    /**
     * Rotates a refresh token into a fresh access + refresh pair.
     *
     * <p>Deliberately NOT transactional: the reuse path must COMMIT its family
     * kill and token_version bump before the 401 propagates — a surrounding
     * transaction would roll the kill back with the exception. Every write here
     * is a single statement, and the rotation itself is one conditional UPDATE,
     * so no multi-statement atomicity is needed.
     */
    public LoginResponse refresh(String rawToken, HttpServletRequest httpRequest) {
        checkRateLimit("refresh", httpRequest,
                properties.getRateLimit().getRefreshMax(),
                properties.getRateLimit().getRefreshWindowSeconds());
        LocalDateTime now = LocalDateTime.now();
        RefreshToken row = rawToken == null || rawToken.isBlank() ? null
                : refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                        .eq(RefreshToken::getTokenHash, sha256Hex(rawToken)));
        if (row == null) {
            throw new BizException(401, INVALID_REFRESH);
        }
        if (row.getRevokedAt() != null) {
            handleRevoked(row, now);
        }
        if (!row.getExpiresAt().isAfter(now)) {
            throw new BizException(401, INVALID_REFRESH);
        }
        UserAccount user = userAccountMapper.selectById(row.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(401, INVALID_REFRESH);
        }
        if (refreshTokenMapper.rotate(row.getId(), now) == 0) {
            // a concurrent refresh won the row between our read and the UPDATE
            RefreshToken reread = refreshTokenMapper.selectById(row.getId());
            if (reread != null && reread.getRevokedAt() != null) {
                handleRevoked(reread, now);
            }
            throw new BizException(401, INVALID_REFRESH);
        }
        Issued issued = issue(user.getId(), row.getFamilyId(), httpRequest);
        RefreshToken link = new RefreshToken();
        link.setId(row.getId());
        link.setReplacedById(issued.rowId());
        refreshTokenMapper.updateById(link);
        return buildResponse(user, issued.raw());
    }

    /**
     * A revoked token was presented. ROTATED within the grace window is a benign
     * race — plain 401, family untouched. Anything else is treated as theft
     * (RFC 9700 reuse detection): the whole family dies and token_version bumps,
     * which also kills every outstanding access token on its next request.
     * Always throws 401.
     */
    private void handleRevoked(RefreshToken row, LocalDateTime now) {
        boolean withinGrace = RefreshToken.CAUSE_ROTATED.equals(row.getRevokeCause())
                && row.getRevokedAt() != null
                && !row.getRevokedAt().plusSeconds(REUSE_GRACE_SECONDS).isBefore(now);
        if (!withinGrace) {
            refreshTokenMapper.revokeFamily(row.getFamilyId(), RefreshToken.CAUSE_REUSE_KILL, now);
            bumpTokenVersion(row.getUserId());
            meterRegistry.counter("linkforge.refresh.reuse_kill").increment();
        }
        throw new BizException(401, INVALID_REFRESH);
    }

    /**
     * Revokes the presented token's family. Idempotent and quiet by design: an
     * unknown token also succeeds, so the endpoint is no validity oracle. Note
     * the asymmetry: the family's ACCESS token stays valid until its natural
     * expiry (at most the configured JWT lifetime) — only logout-all and
     * password change bump token_version and kill access tokens immediately.
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
            refreshTokenMapper.revokeFamily(row.getFamilyId(),
                    RefreshToken.CAUSE_LOGOUT, LocalDateTime.now());
        }
    }

    /** Every session ends now: all refresh rows revoked and token_version bumped,
     * so outstanding access tokens die on their next request too. */
    @Transactional
    public void logoutAll(Long userId) {
        refreshTokenMapper.revokeAllForUser(userId, RefreshToken.CAUSE_LOGOUT, LocalDateTime.now());
        bumpTokenVersion(userId);
    }

    /**
     * Password change is a full sign-out event (batch 2 plan 3.2): the hash is
     * replaced, the forced-change flag clears, token_version bumps and every
     * refresh row is revoked — including the caller's own session, which must
     * log in again with the new password. Nothing survives a credential change.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "invalid credentials");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BizException(401, "invalid credentials");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(0);
        user.setTokenVersion(tokenVersionOf(user) + 1);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(user);
        refreshTokenMapper.revokeAllForUser(userId, RefreshToken.CAUSE_PASSWORD, LocalDateTime.now());
    }

    private record Issued(String raw, Long rowId) {
    }

    /** Mints a raw token (32 random bytes, Base64URL) and stores only its hash. */
    private Issued issue(Long userId, String familyId, HttpServletRequest request) {
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
        refreshTokenMapper.insert(row);
        return new Issued(raw, row.getId());
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

    private void bumpTokenVersion(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            return;
        }
        user.setTokenVersion(tokenVersionOf(user) + 1);
        user.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(user);
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

    static String sha256Hex(String input) {
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

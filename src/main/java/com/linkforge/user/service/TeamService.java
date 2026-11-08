package com.linkforge.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linkforge.apikey.mapper.ApiKeyMapper;
import com.linkforge.auth.RefreshToken;
import com.linkforge.auth.SessionService;
import com.linkforge.auth.mapper.RefreshTokenMapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.exception.RateLimitedException;
import com.linkforge.common.ratelimit.RateLimitDecision;
import com.linkforge.common.ratelimit.RateLimitKeys;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.common.util.ClientIps;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.user.dto.AcceptInvitationRequest;
import com.linkforge.user.dto.CreateInvitationRequest;
import com.linkforge.user.dto.InvitationCreatedResponse;
import com.linkforge.user.dto.InvitationView;
import com.linkforge.user.dto.LoginResponse;
import com.linkforge.user.dto.MemberView;
import com.linkforge.user.dto.ResetPasswordResponse;
import com.linkforge.user.dto.UpdateMemberRequest;
import com.linkforge.user.entity.TenantInvitation;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantInvitationMapper;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Batch 2b team collaboration: bearer invitations and member administration.
 *
 * <p>Every management operation is gated to a tenant ADMIN (the gate mirrors
 * {@code BloomAdminController}: the caller's row is re-read and only a literal
 * {@code "ADMIN"} passes — a stored NULL reads as MEMBER) and scoped to the
 * caller's own tenant; a member id from another tenant is a 404, never a hint
 * that the id exists elsewhere.
 *
 * <p>Credential kills reuse the batch-2a machinery rather than inventing a
 * second mechanism: disabling / removing / resetting a member bumps
 * token_version (access dies at the JWT filter's next-request recheck) and
 * revokes every refresh row; removal additionally flips the existing
 * MyBatis-Plus logical-delete flag, which is what makes the member's API keys
 * die too (the key filter re-reads the owner row per request). The member's
 * links and webhooks are tenant assets and are never touched.
 *
 * <p><b>Audit M2/M7 locking:</b> the member write paths (update / remove /
 * reset) take the tenant row lock as the FIRST statement of their transaction
 * and only then re-authorize the caller, re-read the target and count the
 * active ADMINs — all as locking current reads, never a REPEATABLE READ
 * snapshot established before the lock. Session effects on the target then
 * happen under the target's own user row lock (global order: tenant → user →
 * refresh rows, the same order SessionService uses), with token_version moved
 * only by the atomic SQL increment. A reset is compromise recovery (M7): it
 * also permanently revokes every API key the member holds.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    /** Accept failures never say which check failed (anti-enumeration, plan 5.2). */
    private static final String INVALID_INVITATION = "invalid invitation";

    /** Temporary passwords skip look-alike characters (0/O, 1/l/I) — a human
     * has to read this off one screen and type it into another. */
    private static final String TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 16;

    private final TenantInvitationMapper invitationMapper;
    private final UserAccountMapper userAccountMapper;
    private final TenantMapper tenantMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedisRateLimiter rateLimiter;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final SessionService sessionService;
    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------------
    // invitations
    // ------------------------------------------------------------------

    public InvitationCreatedResponse createInvitation(CreateInvitationRequest request) {
        UserAccount admin = requireAdmin();
        String role = normalizeRole(request.getInvitedRole() == null ? ROLE_MEMBER : request.getInvitedRole());
        String raw = newRawToken();
        LocalDateTime now = LocalDateTime.now();
        TenantInvitation invitation = new TenantInvitation();
        invitation.setTenantId(admin.getTenantId());
        invitation.setTokenHash(SessionService.sha256Hex(raw));
        invitation.setInvitedRole(role);
        invitation.setNoteName(request.getNoteName());
        invitation.setCreatedBy(admin.getId());
        invitation.setExpiresAt(now.plusDays(properties.getAuth().getInvitationExpireDays()));
        invitation.setCreatedAt(now);
        invitationMapper.insert(invitation);
        // the raw token exists only here and in this response — never stored, never logged
        return new InvitationCreatedResponse(invitation.getId(), raw, "/?invite=" + raw,
                role, invitation.getNoteName(), invitation.getExpiresAt());
    }

    public List<InvitationView> listInvitations() {
        UserAccount admin = requireAdmin();
        return invitationMapper.selectList(new LambdaQueryWrapper<TenantInvitation>()
                        .eq(TenantInvitation::getTenantId, admin.getTenantId())
                        .isNull(TenantInvitation::getAcceptedAt)
                        .isNull(TenantInvitation::getRevokedAt)
                        .gt(TenantInvitation::getExpiresAt, LocalDateTime.now())
                        .orderByDesc(TenantInvitation::getId))
                .stream()
                .map(row -> new InvitationView(row.getId(), row.getInvitedRole(), row.getNoteName(),
                        row.getCreatedBy(), row.getCreatedAt(), row.getExpiresAt()))
                .toList();
    }

    public void revokeInvitation(Long invitationId) {
        UserAccount admin = requireAdmin();
        TenantInvitation invitation = invitationMapper.selectById(invitationId);
        if (invitation == null || !invitation.getTenantId().equals(admin.getTenantId())) {
            throw new BizException(404, "invitation not found");
        }
        if (invitation.getAcceptedAt() != null) {
            throw new BizException(400, "invitation already accepted");
        }
        if (invitation.getRevokedAt() == null) {
            TenantInvitation update = new TenantInvitation();
            update.setId(invitation.getId());
            update.setRevokedAt(LocalDateTime.now());
            invitationMapper.updateById(update);
        }
    }

    /**
     * Accepts an invitation and returns a full login response for the new
     * account. One transaction: the user insert and the single-use claim stand
     * or fall together, so a lost accept race rolls the account back instead
     * of leaving an orphan, and a duplicate-username 409 leaves the
     * invitation unconsumed for a retry under a different name.
     */
    @Transactional
    public LoginResponse acceptInvitation(AcceptInvitationRequest request, HttpServletRequest httpRequest) {
        // the budget burns before any lookup: probing token validity stays expensive
        checkRateLimit(httpRequest);
        TenantInvitation invitation = invitationMapper.selectOne(new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getTokenHash, SessionService.sha256Hex(request.getToken())));
        LocalDateTime now = LocalDateTime.now();
        if (invitation == null || invitation.getRevokedAt() != null || invitation.getAcceptedAt() != null
                || !invitation.getExpiresAt().isAfter(now)) {
            throw new BizException(401, INVALID_INVITATION);
        }
        String password = request.getPassword();
        if (password.length() < 8 || password.length() > 64) {
            throw new BizException(400, "password must be 8-64 chars");
        }
        UserAccount user = new UserAccount();
        user.setTenantId(invitation.getTenantId());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(1);
        user.setTenantRole(invitation.getInvitedRole());
        user.setTokenVersion(0);
        user.setMustChangePassword(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userAccountMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // uk_tenant_username backstop (plan A10): the name is taken inside
            // this tenant; the invitation is NOT consumed by the failure.
            throw new BizException(409, "username already exists in this tenant", ex);
        }
        if (invitationMapper.accept(invitation.getId(), user.getId(), now) == 0) {
            // a concurrent accept won the row between our read and this UPDATE
            throw new BizException(401, INVALID_INVITATION);
        }
        return sessionService.issueLoginResponse(user, httpRequest);
    }

    // ------------------------------------------------------------------
    // members
    // ------------------------------------------------------------------

    public List<MemberView> listMembers() {
        UserAccount admin = requireAdmin();
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getTenantId, admin.getTenantId())
                        .orderByAsc(UserAccount::getId))
                .stream()
                .map(TeamService::toMemberView)
                .toList();
    }

    /**
     * Role and/or status change. Audit M2: the tenant row lock is the FIRST
     * statement of this transaction; the caller's authority, the target row
     * and the active-ADMIN set are all (re-)read after it as locking current
     * reads, so concurrent demotions queue on the lock and the second one
     * evaluates the guard against the world the first one left behind — a
     * REPEATABLE READ snapshot taken before the lock can no longer smuggle
     * a stale "two ADMINs" view into the check. A role change alone revokes
     * nothing — the filters read the role from the row per request, so the
     * new role simply takes effect on the next request. Disabling is a
     * session kill (atomic version increment + refresh revocation, under the
     * target's user row lock taken by the re-read above).
     */
    @Transactional
    public MemberView updateMember(Long targetId, UpdateMemberRequest request) {
        AuthUser caller = requireCaller();
        tenantMapper.selectForUpdate(caller.getTenantId());
        requireAdminUnderLock(caller);
        UserAccount target = requireTenantMemberUnderLock(caller.getTenantId(), targetId);

        String newRole = target.getTenantRole();
        if (request.getRole() != null) {
            newRole = normalizeRole(request.getRole());
        }
        Integer newStatus = target.getStatus();
        if (request.getStatus() != null) {
            if (request.getStatus() != 0 && request.getStatus() != 1) {
                throw new BizException(400, "status must be 0 or 1");
            }
            newStatus = request.getStatus();
        }

        boolean losesActiveAdmin = isActiveAdmin(target)
                && (!ROLE_ADMIN.equals(newRole) || !Integer.valueOf(1).equals(newStatus));
        if (losesActiveAdmin
                && userAccountMapper.selectActiveAdminIdsForUpdate(caller.getTenantId()).size() <= 1) {
            throw new BizException(400, "the tenant's last ADMIN cannot be demoted or disabled");
        }

        boolean disabling = Integer.valueOf(1).equals(target.getStatus())
                && Integer.valueOf(0).equals(newStatus);
        LocalDateTime now = LocalDateTime.now();
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, target.getId())
                .set(UserAccount::getTenantRole, newRole)
                .set(UserAccount::getStatus, newStatus)
                .set(UserAccount::getUpdatedAt, now));
        if (disabling) {
            userAccountMapper.incrementTokenVersion(target.getId());
            refreshTokenMapper.revokeAllForUser(target.getId(),
                    RefreshToken.CAUSE_ADMIN, now);
        }
        UserAccount after = new UserAccount();
        after.setId(target.getId());
        after.setUsername(target.getUsername());
        after.setTenantRole(newRole);
        after.setStatus(newStatus);
        after.setCreatedAt(target.getCreatedAt());
        return toMemberView(after);
    }

    /**
     * Logical removal: credentials die on the next request (access via the
     * filter recheck, refresh revoked here, API keys via the owner recheck on
     * the now-invisible row), the member vanishes from the list, and the row —
     * plus every link/webhook it created — stays as a tenant asset.
     */
    @Transactional
    public void removeMember(Long targetId) {
        AuthUser caller = requireCaller();
        tenantMapper.selectForUpdate(caller.getTenantId());
        requireAdminUnderLock(caller);
        UserAccount target = requireTenantMemberUnderLock(caller.getTenantId(), targetId);
        if (target.getId().equals(caller.getUserId())) {
            throw new BizException(400, "you cannot remove yourself");
        }
        if (isActiveAdmin(target)
                && userAccountMapper.selectActiveAdminIdsForUpdate(caller.getTenantId()).size() <= 1) {
            throw new BizException(400, "the tenant's last ADMIN cannot be removed");
        }
        userAccountMapper.incrementTokenVersion(target.getId());
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, target.getId())
                .set(UserAccount::getUpdatedAt, LocalDateTime.now()));
        refreshTokenMapper.revokeAllForUser(target.getId(),
                RefreshToken.CAUSE_ADMIN, LocalDateTime.now());
        userAccountMapper.deleteById(target.getId());
    }

    /**
     * The batch's password-recovery path (plan D3): a one-time temporary
     * password, shown once in this response and never logged, with the
     * forced-change flag set so the member must replace it before doing
     * anything else. Every existing session of the member dies with it.
     *
     * <p>Audit M7: a reset is compromise recovery, not a courtesy unlock —
     * in the same locked transaction (tenant lock → caller re-authorized →
     * target user lock) it also permanently revokes EVERY API key the member
     * holds, USER and TENANT scope alike. Old keys never come back; the
     * member mints fresh ones only after the forced password change, and
     * ApiKeyService refuses creation while the flag is set, so a key created
     * in a race with this reset is either revoked by it or rejected after it.
     */
    @Transactional
    public ResetPasswordResponse resetPassword(Long targetId) {
        AuthUser caller = requireCaller();
        tenantMapper.selectForUpdate(caller.getTenantId());
        requireAdminUnderLock(caller);
        UserAccount target = requireTenantMemberUnderLock(caller.getTenantId(), targetId);
        String temporary = newTemporaryPassword();
        LocalDateTime now = LocalDateTime.now();
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, target.getId())
                .set(UserAccount::getPasswordHash, passwordEncoder.encode(temporary))
                .set(UserAccount::getMustChangePassword, 1)
                .set(UserAccount::getUpdatedAt, now));
        userAccountMapper.incrementTokenVersion(target.getId());
        refreshTokenMapper.revokeAllForUser(target.getId(),
                RefreshToken.CAUSE_PASSWORD, now);
        apiKeyMapper.revokeAllForUser(target.getId(), now);
        return new ResetPasswordResponse(temporary);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The ADMIN gate, mirroring BloomAdminController: identity comes from the
     * caller's DB row (never the token), and only a literal "ADMIN" passes. */
    private UserAccount requireAdmin() {
        AuthUser caller = UserContext.get();
        if (caller == null) {
            throw new BizException(401, "unauthorized");
        }
        UserAccount account = userAccountMapper.selectById(caller.getUserId());
        if (account == null || !ROLE_ADMIN.equals(account.getTenantRole())) {
            throw new BizException(403, "only a tenant ADMIN can manage invitations and members");
        }
        return account;
    }

    /** The authenticated principal, before any database statement. Identity
     * and tenant come from the principal (never from a pre-lock DB read); the
     * AUTHORITY is then re-established under the tenant lock by
     * {@link #requireAdminUnderLock}. */
    private AuthUser requireCaller() {
        AuthUser caller = UserContext.get();
        if (caller == null) {
            throw new BizException(401, "unauthorized");
        }
        return caller;
    }

    /**
     * Audit M2 re-authorization: called ONLY after the tenant row lock is
     * held. The caller's row is re-read as a locking current read and must be
     * an active ADMIN of the principal's tenant right now — a caller demoted
     * or disabled while its request queued on the lock is refused here, at
     * execution time, not on the strength of a stale pre-lock snapshot.
     */
    private void requireAdminUnderLock(AuthUser caller) {
        UserAccount account = userAccountMapper.selectForUpdate(caller.getUserId());
        if (account == null || isDeleted(account)
                || !account.getTenantId().equals(caller.getTenantId())
                || !isActiveAdmin(account)) {
            throw new BizException(403, "only a tenant ADMIN can manage invitations and members");
        }
    }

    /** Tenant-scoped member load under the tenant lock: a locking current
     * read, so the row the guard evaluates is the row the write then acts on.
     * Another tenant's id is indistinguishable from a nonexistent one, and a
     * removed member (the raw locking read bypasses logic-delete) lands here
     * as 404 too. */
    private UserAccount requireTenantMemberUnderLock(Long tenantId, Long targetId) {
        UserAccount target = userAccountMapper.selectForUpdate(targetId);
        if (target == null || isDeleted(target) || !target.getTenantId().equals(tenantId)) {
            throw new BizException(404, "member not found");
        }
        return target;
    }

    private static boolean isDeleted(UserAccount user) {
        return user.getDeleted() != null && user.getDeleted() == 1;
    }

    private static MemberView toMemberView(UserAccount user) {
        return new MemberView(user.getId(), user.getUsername(), roleOf(user),
                user.getStatus(), user.getCreatedAt());
    }

    /** Effective role: only a literal "ADMIN" is ADMIN; NULL (pre-backfill
     * rows) and anything else read as MEMBER. */
    static String roleOf(UserAccount user) {
        return ROLE_ADMIN.equals(user.getTenantRole()) ? ROLE_ADMIN : ROLE_MEMBER;
    }

    private static boolean isActiveAdmin(UserAccount user) {
        return ROLE_ADMIN.equals(user.getTenantRole())
                && user.getStatus() != null && user.getStatus() == 1;
    }

    private static String normalizeRole(String role) {
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_ADMIN.equals(normalized) && !ROLE_MEMBER.equals(normalized)) {
            throw new BizException(400, "role must be ADMIN or MEMBER");
        }
        return normalized;
    }

    /** 32 random bytes, Base64URL — the same token shape as refresh tokens. */
    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_ALPHABET.charAt(random.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private void checkRateLimit(HttpServletRequest request) {
        LinkForgeProperties.RateLimit limit = properties.getRateLimit();
        RateLimitDecision decision = rateLimiter.acquire(
                RateLimitKeys.sw(limit.getKeyPrefix(),
                        "accept-invitation:" + ClientIps.resolve(request, limit.isTrustXff())),
                limit.getAcceptInvitationMax(), limit.getAcceptInvitationWindowSeconds());
        if (!decision.allowed()) {
            meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "accept-invitation").increment();
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
    }
}

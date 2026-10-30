package com.linkforge.apikey.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.apikey.dto.ApiKeyDtos;
import com.linkforge.apikey.entity.ApiKey;
import com.linkforge.apikey.mapper.ApiKeyMapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.AuthUser;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX_TAG = "lfk_";
    private static final int PREFIX_LENGTH = 8;
    private static final int SECRET_LENGTH = 32;
    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ApiKeyMapper apiKeyMapper;
    private final UserAccountMapper userAccountMapper;
    private final LinkForgeProperties properties;
    private final SecureRandom random = new SecureRandom();

    /**
     * Mints lfk_{prefix8}_{secret}; only the SHA-256 hex of the whole plaintext is
     * stored. The plaintext is returned once in the response and never logged.
     */
    public ApiKeyDtos.Created create(ApiKeyDtos.CreateRequest request, AuthUser user) {
        String scope = request.getScope() == null || request.getScope().isBlank()
                ? "USER" : request.getScope().trim().toUpperCase();
        if (!"USER".equals(scope) && !"TENANT".equals(scope)) {
            throw new BizException(400, "scope must be USER or TENANT");
        }
        if ("TENANT".equals(scope)) {
            UserAccount creator = userAccountMapper.selectById(user.getUserId());
            // pre-V8 rows have a null role, which reads as MEMBER
            if (creator == null || !"ADMIN".equals(creator.getTenantRole())) {
                throw new BizException(403, "only a tenant ADMIN can mint a TENANT-scope api key");
            }
        }
        long active = apiKeyMapper.selectCount(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, user.getUserId())
                .isNull(ApiKey::getRevokedAt));
        if (active >= properties.getApiKey().getMaxActivePerUser()) {
            throw new BizException(400, "active api key limit reached ("
                    + properties.getApiKey().getMaxActivePerUser() + ")");
        }
        LinkForgeProperties.ApiKey cfg = properties.getApiKey();
        if (request.getQuotaPerMinute() != null && request.getQuotaPerMinute() > cfg.getMaxQuotaPerMinute()) {
            throw new BizException(400, "quotaPerMinute exceeds the cap of " + cfg.getMaxQuotaPerMinute());
        }
        if (request.getQuotaPerDay() != null && request.getQuotaPerDay() > cfg.getMaxQuotaPerDay()) {
            throw new BizException(400, "quotaPerDay exceeds the cap of " + cfg.getMaxQuotaPerDay());
        }

        String prefix = randomBase62(PREFIX_LENGTH);
        String secret = randomBase62(SECRET_LENGTH);
        String plaintext = KEY_PREFIX_TAG + prefix + "_" + secret;

        ApiKey key = new ApiKey();
        key.setTenantId(user.getTenantId());
        key.setUserId(user.getUserId());
        key.setScope(scope);
        key.setName(request.getName());
        key.setKeyPrefix(KEY_PREFIX_TAG + prefix);
        key.setKeyHash(sha256Hex(plaintext));
        key.setQuotaPerMinute(request.getQuotaPerMinute());
        key.setQuotaPerDay(request.getQuotaPerDay());
        key.setCreatedAt(LocalDateTime.now());
        apiKeyMapper.insert(key);

        ApiKeyDtos.Created created = new ApiKeyDtos.Created();
        created.setId(key.getId());
        created.setName(key.getName());
        created.setPrefix(key.getKeyPrefix());
        created.setKey(plaintext);
        created.setScope(scope);
        created.setCreatedBy(key.getUserId());
        created.setQuotaPerMinute(key.getQuotaPerMinute());
        created.setQuotaPerDay(key.getQuotaPerDay());
        created.setCreatedAt(key.getCreatedAt());
        return created;
    }

    public List<ApiKeyDtos.View> listOwn(AuthUser user) {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getUserId, user.getUserId())
                        .orderByDesc(ApiKey::getId)).stream()
                .map(ApiKeyService::toView)
                .toList();
    }

    /** Idempotent revoke; a key owned by someone else is a plain 404 (no existence leak). */
    public void revoke(AuthUser user, long id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null || !key.getUserId().equals(user.getUserId())) {
            throw new BizException(404, "api key not found");
        }
        if (key.getRevokedAt() != null) {
            return;
        }
        key.setRevokedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);
    }

    /** Point lookup on the unique hash index; null when the hash is unknown. Revoked keys
     * come back as-is so the filter can tag them (result=revoked) and reject uniformly. */
    public ApiKey authenticate(String plaintextKey) {
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, sha256Hex(plaintextKey)));
    }

    /** Best-effort last_used_at stamp; the caller gates it to once per minute via SET NX. */
    public void touch(long id) {
        apiKeyMapper.touchLastUsed(id, LocalDateTime.now());
    }

    static ApiKeyDtos.View toView(ApiKey key) {
        ApiKeyDtos.View view = new ApiKeyDtos.View();
        view.setId(key.getId());
        view.setName(key.getName());
        view.setPrefix(key.getKeyPrefix());
        view.setScope(key.getScope());
        view.setCreatedBy(key.getUserId());
        view.setQuotaPerMinute(key.getQuotaPerMinute());
        view.setQuotaPerDay(key.getQuotaPerDay());
        view.setLastUsedAt(key.getLastUsedAt());
        view.setRevokedAt(key.getRevokedAt());
        view.setCreatedAt(key.getCreatedAt());
        return view;
    }

    private String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
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

package com.linkforge.link.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.common.result.PageResponse;
import com.linkforge.common.util.ClientIps;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.dto.CreateShortLinkRequest;
import com.linkforge.link.dto.CreateShortLinkResponse;
import com.linkforge.link.dto.ShortLinkDetail;
import com.linkforge.link.dto.UpdateShortLinkRequest;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.mapper.ShortLinkMapper;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.stats.service.ClickRecordService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private static final String CACHE_KEY_PREFIX = "linkforge:code:";
    private static final char[] CODE_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_ATTEMPTS = 16;
    private static final long STALE_WRITEBACK_CLEAR_MS = 1000;

    private final ShortLinkMapper shortLinkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LinkForgeProperties properties;
    private final ClickRecordService clickRecordService;
    private final RedisRateLimiter rateLimiter;

    private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-evictor");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownEvictor() {
        evictor.shutdown();
    }

    public CreateShortLinkResponse create(CreateShortLinkRequest request, String idempotencyKey) {
        AuthUser user = requireUser();
        LinkForgeProperties.RateLimit rl = properties.getRateLimit();
        String rlKey = rl.getKeyPrefix() + "create:user:" + user.getUserId();
        if (!rateLimiter.tryAcquire(rlKey, rl.getCreateMax(), rl.getCreateWindowSeconds())) {
            throw new BizException(429, "too many requests, retry later");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String idemKey = rl.getKeyPrefix() + "idem:" + user.getUserId() + ":" + idempotencyKey;
            CreateShortLinkResponse replayed = replayIdempotentCreate(idemKey, user);
            if (replayed != null) {
                return replayed;
            }
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(idemKey, "pending", Duration.ofSeconds(5));
            if (Boolean.TRUE.equals(acquired)) {
                return doCreate(request, user, idemKey);
            }
            for (int i = 0; i < 10; i++) {
                sleepQuietly(200);
                CreateShortLinkResponse resolved = replayIdempotentCreate(idemKey, user);
                if (resolved != null) {
                    return resolved;
                }
            }
            return doCreate(request, user, idemKey);
        }
        return doCreate(request, user, null);
    }

    /**
     * Returns the previously created link for an idempotency key, or null when the key
     * holds no settled code. A code owned by a different user is treated as a miss.
     */
    private CreateShortLinkResponse replayIdempotentCreate(String idemKey, AuthUser user) {
        String code = stringRedisTemplate.opsForValue().get(idemKey);
        if (code == null || code.isBlank() || "pending".equals(code)) {
            return null;
        }
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        if (link == null || link.getCreatedBy() == null || !link.getCreatedBy().equals(user.getUserId())) {
            return null;
        }
        return new CreateShortLinkResponse(link.getId(), link.getCode(), link.getOriginalUrl(),
                "/r/" + link.getCode());
    }

    private CreateShortLinkResponse doCreate(CreateShortLinkRequest request, AuthUser user, String idemKey) {
        String url = request.getOriginalUrl().trim();
        requireHttpUrl(url);
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException("expireAt must be in the future");
        }

        ShortLink link = new ShortLink();
        link.setTenantId(user.getTenantId());
        link.setOriginalUrl(url);
        link.setTitle(request.getTitle());
        link.setStatus(1);
        link.setExpireAt(request.getExpireAt());
        link.setCreatedBy(user.getUserId());
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());

        // The uk_code unique index is the concurrency backstop; on a collision a fresh
        // random code is tried instead of failing the request.
        boolean inserted = false;
        for (int attempt = 0; attempt < CODE_ATTEMPTS && !inserted; attempt++) {
            link.setCode(randomCode(properties.getShortLink().getCodeLength()));
            try {
                shortLinkMapper.insert(link);
                inserted = true;
            } catch (DuplicateKeyException ex) {
                log.info("short code collision on {}, retrying (attempt {})", link.getCode(), attempt + 1);
            }
        }
        if (!inserted) {
            throw new BizException("failed to allocate short code");
        }

        cacheUrl(link.getCode(), url);
        if (idemKey != null) {
            stringRedisTemplate.opsForValue().set(idemKey, link.getCode(),
                    Duration.ofSeconds(properties.getRateLimit().getIdempotencyTtlSeconds()));
        }
        return new CreateShortLinkResponse(link.getId(), link.getCode(), url, "/r/" + link.getCode());
    }

    public PageResponse<ShortLinkDetail> list(int page, int size) {
        AuthUser user = requireUser();
        long current = Math.max(1, page);
        long pageSize = size < 1 || size > 100 ? 20 : size;
        Page<ShortLink> result = shortLinkMapper.selectPage(new Page<>(current, pageSize),
                new LambdaQueryWrapper<ShortLink>()
                        .eq(ShortLink::getCreatedBy, user.getUserId())
                        .orderByDesc(ShortLink::getId));
        List<ShortLinkDetail> records = result.getRecords().stream().map(ShortLinkService::toDetail).toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    public ShortLinkDetail detail(String code) {
        return toDetail(getOwnedLink(code));
    }

    public ShortLinkDetail update(String code, UpdateShortLinkRequest request) {
        boolean hasUrl = request.getOriginalUrl() != null && !request.getOriginalUrl().isBlank();
        if (!hasUrl && request.getTitle() == null) {
            throw new BizException("nothing to update");
        }
        ShortLink link = getOwnedLink(code);
        String effectiveUrl = link.getOriginalUrl();
        if (hasUrl) {
            String url = request.getOriginalUrl().trim();
            requireHttpUrl(url);
            effectiveUrl = url;
            link.setOriginalUrl(url);
        }
        if (request.getTitle() != null) {
            link.setTitle(request.getTitle());
        }
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.updateById(link);
        // Write-through now, then a delayed delete clears any stale write-back a racing
        // reader may have cached from the pre-commit row. Final consistency window ~1s.
        cacheUrl(code, effectiveUrl);
        scheduleStaleClear(code);
        return toDetail(link);
    }

    public void disable(String code) {
        ShortLink link = getOwnedLink(code);
        link.setStatus(0);
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.updateById(link);
        evictCache(code);
        scheduleStaleClear(code);
    }

    /**
     * Resolve redirect URL: Redis first, then MySQL. The DB row is always read so a
     * disabled or expired link is rejected even while a stale cache entry exists.
     * Records the click synchronously; optionally publishes to MQ when enabled.
     */
    public String resolveAndRecord(String code, HttpServletRequest request) {
        LinkForgeProperties.RateLimit rl = properties.getRateLimit();
        String rlKey = rl.getKeyPrefix() + "redirect:ip:" + ClientIps.resolve(request);
        if (!rateLimiter.tryAcquire(rlKey, rl.getRedirectMax(), rl.getRedirectWindowSeconds())) {
            throw new BizException(429, "too many requests, retry later");
        }

        String cacheKey = CACHE_KEY_PREFIX + code;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        if (link == null || link.getStatus() == null || link.getStatus() != 1) {
            throw new BizException(404, "short link not found");
        }
        if (link.getExpireAt() != null && link.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(410, "short link expired");
        }

        String originalUrl = cached != null && !cached.isBlank() ? cached : link.getOriginalUrl();
        if (cached == null || cached.isBlank()) {
            cacheUrl(code, originalUrl);
        }
        clickRecordService.recordClick(link, request);
        return originalUrl;
    }

    private ShortLink getOwnedLink(String code) {
        AuthUser user = requireUser();
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        if (link == null || link.getCreatedBy() == null || !link.getCreatedBy().equals(user.getUserId())) {
            throw new BizException(404, "short link not found");
        }
        return link;
    }

    private static AuthUser requireUser() {
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "unauthorized");
        }
        return user;
    }

    private static void requireHttpUrl(String url) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException("originalUrl must start with http:// or https://");
        }
    }

    private void cacheUrl(String code, String url) {
        long ttl = properties.getShortLink().getCacheTtlSeconds();
        stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + code, url, ttl, TimeUnit.SECONDS);
    }

    private void evictCache(String code) {
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + code);
    }

    private void scheduleStaleClear(String code) {
        evictor.schedule(() -> {
            try {
                evictCache(code);
            } catch (Exception ex) {
                log.warn("delayed cache clear failed for {}: {}", code, ex.getMessage());
            }
        }, STALE_WRITEBACK_CLEAR_MS, TimeUnit.MILLISECONDS);
    }

    private static String randomCode(int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(buf);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static ShortLinkDetail toDetail(ShortLink link) {
        return new ShortLinkDetail(link.getId(), link.getCode(), link.getOriginalUrl(), link.getTitle(),
                link.getStatus(), link.getExpireAt(), link.getCreatedAt(), link.getUpdatedAt());
    }
}

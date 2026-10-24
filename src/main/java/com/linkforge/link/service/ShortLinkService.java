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
import com.linkforge.link.support.CodeBloomFilter;
import com.linkforge.link.support.CsvFormat;
import com.linkforge.link.support.LinkValidations;
import com.linkforge.link.support.ShortCodes;
import com.linkforge.security.AuthUser;
import com.linkforge.security.UserContext;
import com.linkforge.stats.service.ClickRecordService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
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
    private static final int CODE_ATTEMPTS = 16;
    private static final long STALE_WRITEBACK_CLEAR_MS = 1000;

    private final ShortLinkMapper shortLinkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LinkForgeProperties properties;
    private final ClickRecordService clickRecordService;
    private final RedisRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final CodeBloomFilter codeBloom;

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
            meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "create").increment();
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
            // The winner has not settled its code; forcing a second create could mint
            // two links under one Idempotency-Key, so the caller retries instead.
            throw new BizException(503, "idempotent create still in progress, retry later");
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
        LinkValidations.requireHttpUrl(url);
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException("expireAt must be in the future");
        }
        String customCode = request.getCustomCode() == null ? null : request.getCustomCode().trim();
        if (customCode != null && !customCode.isEmpty()) {
            LinkValidations.requireValidCustomCode(customCode);
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

        // uk_code is the concurrency backstop. A taken custom code is a 409; random
        // codes regenerate on collision instead of failing the request.
        boolean inserted = false;
        if (customCode != null && !customCode.isEmpty()) {
            link.setCode(customCode);
            try {
                shortLinkMapper.insert(link);
                inserted = true;
            } catch (DuplicateKeyException ex) {
                throw new BizException(409, "code already exists");
            }
        } else {
            for (int attempt = 0; attempt < CODE_ATTEMPTS && !inserted; attempt++) {
                link.setCode(ShortCodes.randomCode(properties.getShortLink().getCodeLength()));
                try {
                    shortLinkMapper.insert(link);
                    inserted = true;
                } catch (DuplicateKeyException ex) {
                    log.info("short code collision on {}, retrying (attempt {})", link.getCode(), attempt + 1);
                }
            }
        }
        if (!inserted) {
            throw new BizException("failed to allocate short code");
        }

        cacheUrl(link.getCode(), url);
        codeBloom.put(link.getCode());
        meterRegistry.counter("linkforge.links.created").increment();
        if (idemKey != null) {
            stringRedisTemplate.opsForValue().set(idemKey, link.getCode(),
                    Duration.ofSeconds(properties.getRateLimit().getIdempotencyTtlSeconds()));
        }
        return new CreateShortLinkResponse(link.getId(), link.getCode(), url, "/r/" + link.getCode());
    }

    public PageResponse<ShortLinkDetail> list(int page, int size) {
        AuthUser user = requireUser();
        return pageOwn(user.getUserId(), Math.max(1, page), size < 1 || size > 100 ? 20 : size);
    }

    private PageResponse<ShortLinkDetail> pageOwn(Long userId, long current, long pageSize) {
        Page<ShortLink> result = shortLinkMapper.selectPage(new Page<>(current, pageSize),
                new LambdaQueryWrapper<ShortLink>()
                        .eq(ShortLink::getCreatedBy, userId)
                        .orderByDesc(ShortLink::getId));
        List<ShortLinkDetail> records = result.getRecords().stream().map(ShortLinkService::toDetail).toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    /** CSV of every own link, capped at 10k rows; BOM keeps Excel happy with UTF-8. */
    public String exportOwnCsv() {
        AuthUser user = requireUser();
        StringBuilder sb = new StringBuilder("\uFEFFcode,original_url,title,status,expire_at,created_at\n");
        int exported = 0;
        long page = 1;
        while (exported < 10000) {
            PageResponse<ShortLinkDetail> chunk = pageOwn(user.getUserId(), page++, 500);
            for (ShortLinkDetail link : chunk.getRecords()) {
                sb.append(csvRow(link));
                exported++;
            }
            if (chunk.getRecords().isEmpty() || chunk.getCurrent() >= chunk.getPages()) {
                break;
            }
        }
        return sb.toString();
    }

    private static String csvRow(ShortLinkDetail l) {
        return String.join(",",
                CsvFormat.escape(l.getCode()),
                CsvFormat.escape(l.getOriginalUrl()),
                CsvFormat.escape(l.getTitle()),
                CsvFormat.escape(l.getStatus() == null ? "" : String.valueOf(l.getStatus())),
                CsvFormat.escape(l.getExpireAt() == null ? "" : l.getExpireAt().toString()),
                CsvFormat.escape(l.getCreatedAt() == null ? "" : l.getCreatedAt().toString())) + "\n";
    }

    public ShortLinkDetail detail(String code) {
        return toDetail(getOwnedLink(code));
    }

    public ShortLinkDetail update(String code, UpdateShortLinkRequest request) {
        boolean hasUrl = request.getOriginalUrl() != null && !request.getOriginalUrl().isBlank();
        if (!hasUrl && request.getTitle() == null && request.getExpireAt() == null) {
            throw new BizException("nothing to update");
        }
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException("expireAt must be in the future");
        }
        ShortLink link = getOwnedLink(code);
        String effectiveUrl = link.getOriginalUrl();
        if (hasUrl) {
            String url = request.getOriginalUrl().trim();
            LinkValidations.requireHttpUrl(url);
            effectiveUrl = url;
            link.setOriginalUrl(url);
        }
        if (request.getTitle() != null) {
            link.setTitle(request.getTitle());
        }
        if (request.getExpireAt() != null) {
            link.setExpireAt(request.getExpireAt());
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
        setStatus(code, 0);
    }

    public void enable(String code) {
        setStatus(code, 1);
    }

    private void setStatus(String code, int status) {
        ShortLink link = getOwnedLink(code);
        link.setStatus(status);
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.updateById(link);
        evictCache(code);
        scheduleStaleClear(code);
    }

    /** Logical delete: the code stays reserved (uk_code) and cannot be reused. */
    public void delete(String code) {
        ShortLink link = getOwnedLink(code);
        shortLinkMapper.deleteById(link.getId());
        evictCache(code);
        scheduleStaleClear(code);
    }

    /**
     * Resolve redirect URL: Redis first, then MySQL. The DB row is always read so a
     * disabled or expired link is rejected even while a stale cache entry exists.
     * Records the click synchronously; optionally publishes to MQ when enabled.
     */
    public String resolveAndRecord(String code, HttpServletRequest request) {
        ResolvedRedirect resolved = resolveTarget(code, request);
        clickRecordService.recordClick(resolved.link(), request);
        meterRegistry.counter("linkforge.redirects").increment();
        return resolved.url();
    }

    /**
     * HEAD probe variant: same jumpability semantics (rate limit, 404/410) but records
     * no click, publishes nothing and does not bump the redirects counter.
     */
    public String resolveForProbe(String code, HttpServletRequest request) {
        return resolveTarget(code, request).url();
    }

    /**
     * Shared resolution for GET and HEAD: redirect rate-limit bucket, Bloom gate
     * (never-created codes 404 pre-DB), cache read, row state/expiry check and
     * cache warm-up, one DB read for both callers.
     */
    private ResolvedRedirect resolveTarget(String code, HttpServletRequest request) {
        LinkForgeProperties.RateLimit rl = properties.getRateLimit();
        String rlKey = rl.getKeyPrefix() + "redirect:ip:" + ClientIps.resolve(request, rl.isTrustXff());
        if (!rateLimiter.tryAcquire(rlKey, rl.getRedirectMax(), rl.getRedirectWindowSeconds())) {
            meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "redirect").increment();
            throw new BizException(429, "too many requests, retry later");
        }

        // A9: a negative Bloom answer is authoritative (code never created) — 404
        // without touching MySQL. False positives and disabled/broken states fall
        // through to the cache+DB path below, which stays the correctness arbiter.
        if (!codeBloom.mightContain(code)) {
            meterRegistry.counter("linkforge.bloom.rejected").increment();
            throw new BizException(404, "short link not found");
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
        if (!LinkValidations.isRenderableUrl(originalUrl)) {
            throw new BizException(410, "short link target is invalid");
        }
        if (cached == null || cached.isBlank()) {
            cacheUrl(code, originalUrl);
        }
        return new ResolvedRedirect(link, originalUrl);
    }

    private record ResolvedRedirect(ShortLink link, String url) {
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

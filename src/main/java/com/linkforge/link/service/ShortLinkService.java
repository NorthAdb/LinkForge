package com.linkforge.link.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.exception.RateLimitedException;
import com.linkforge.common.ratelimit.RateLimitDecision;
import com.linkforge.common.ratelimit.RateLimitKeys;
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
import com.linkforge.link.support.CacheBus;
import com.linkforge.link.support.CodeBloomFilter;
import com.linkforge.link.support.ClickFilter;
import com.linkforge.link.support.CsvFormat;
import com.linkforge.link.support.LinkAccessTokens;
import com.linkforge.link.support.LinkValidations;
import com.linkforge.link.support.PhishingPolicy;
import com.linkforge.link.support.ShortCodes;
import com.linkforge.link.support.ShortLinkRowLoader;
import com.linkforge.link.support.SingleFlight;
import com.linkforge.link.support.TargetPolicy;
import com.linkforge.link.support.UrlLocalCache;
import com.linkforge.link.support.UtmMerger;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private static final String CACHE_KEY_PREFIX = "linkforge:code:";
    private static final String ABSENT_KEY_PREFIX = "linkforge:absent:";
    private static final String ABSENT_SENTINEL = "1";
    private static final int CODE_ATTEMPTS = 16;
    private static final long STALE_WRITEBACK_CLEAR_MS = 1000;
    /** D-C1-8: captured unknown props that would smuggle click attribution are a 400;
     * every other unknown prop keeps the silent-ignore contract. */
    private static final Pattern UTM_PREFIX = Pattern.compile("^utm", Pattern.CASE_INSENSITIVE);
    private static final Set<String> CLICK_ATTRIBUTION_IDS = Set.of(
            "gclid", "fbclid", "msclkid", "ttclid", "yclid", "dclid", "igshid", "wbraid", "gbraid");

    private final ShortLinkMapper shortLinkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LinkForgeProperties properties;
    private final ClickRecordService clickRecordService;
    private final RedisRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final CodeBloomFilter codeBloom;
    private final CacheBus cacheBus;
    private final SingleFlight<String, ShortLink> singleFlight;
    private final ShortLinkRowLoader rowLoader;
    private final UrlLocalCache urlLocalCache;
    private final ClickFilter clickFilter;
    private final PhishingPolicy phishingPolicy;
    private final LinkAccessTokens linkAccessTokens;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
        RateLimitDecision decision = rateLimiter.acquire(
                RateLimitKeys.sw(rl.getKeyPrefix(), "create:user:" + user.getUserId()),
                rl.getCreateMax(), rl.getCreateWindowSeconds());
        if (!decision.allowed()) {
            meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "create").increment();
            throw new RateLimitedException(decision.retryAfterSeconds());
        }

        rejectSmuggledAttribution(request.getUnknown());
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
        String url = mergeUtm(request.getOriginalUrl().trim(), UtmMerger.canonical(request.getUtmSource(),
                request.getUtmMedium(), request.getUtmCampaign(), request.getUtmTerm(), request.getUtmContent()));
        LinkValidations.requireHttpUrl(url);
        rejectBlockedTarget(url);
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException("expireAt must be in the future");
        }
        String customCode = request.getCustomCode() == null ? null : request.getCustomCode().trim();
        if (customCode != null && !customCode.isEmpty()) {
            LinkValidations.requireValidCustomCode(customCode);
        }
        String passwordHash = encodeAccessPassword(request.getAccessPassword());

        ShortLink link = new ShortLink();
        link.setTenantId(user.getTenantId());
        link.setOriginalUrl(url);
        link.setTitle(request.getTitle());
        link.setStatus(1);
        link.setExpireAt(request.getExpireAt());
        link.setCreatedBy(user.getUserId());
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());
        link.setAccessPasswordHash(passwordHash);

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

        codeBloom.put(link.getCode());
        cacheBus.publishBloomPut(link.getCode());
        clearAbsent(link.getCode());
        cacheUrl(link.getCode(), url);
        meterRegistry.counter("linkforge.links.created").increment();
        if (idemKey != null) {
            stringRedisTemplate.opsForValue().set(idemKey, link.getCode(),
                    Duration.ofSeconds(properties.getRateLimit().getIdempotencyTtlSeconds()));
        }
        return new CreateShortLinkResponse(link.getId(), link.getCode(), url, "/r/" + link.getCode());
    }

    public PageResponse<ShortLinkDetail> list(int page, int size) {
        AuthUser user = requireUser();
        return pageOwn(user, Math.max(1, page), size < 1 || size > 100 ? 20 : size);
    }

    private PageResponse<ShortLinkDetail> pageOwn(AuthUser user, long current, long pageSize) {
        LambdaQueryWrapper<ShortLink> query = new LambdaQueryWrapper<ShortLink>()
                .orderByDesc(ShortLink::getId);
        if (isTenantWide(user)) {
            query.eq(ShortLink::getTenantId, user.getTenantId());
        } else {
            query.eq(ShortLink::getCreatedBy, user.getUserId());
        }
        Page<ShortLink> result = shortLinkMapper.selectPage(new Page<>(current, pageSize), query);
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
            PageResponse<ShortLinkDetail> chunk = pageOwn(user, page++, 500);
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
        rejectSmuggledAttribution(request.getUnknown());
        boolean hasUrl = request.getOriginalUrl() != null && !request.getOriginalUrl().isBlank();
        boolean clearPassword = Boolean.TRUE.equals(request.getClearAccessPassword());
        boolean setPassword = request.getAccessPassword() != null && !request.getAccessPassword().isBlank();
        Map<String, String> utm = UtmMerger.canonical(request.getUtmSource(), request.getUtmMedium(),
                request.getUtmCampaign(), request.getUtmTerm(), request.getUtmContent());
        if (!hasUrl && request.getTitle() == null && request.getExpireAt() == null
                && !clearPassword && !setPassword && utm.isEmpty()) {
            throw new BizException("nothing to update");
        }
        if (clearPassword && setPassword) {
            throw new BizException("accessPassword and clearAccessPassword are mutually exclusive");
        }
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException("expireAt must be in the future");
        }
        ShortLink link = getOwnedLink(code);
        String effectiveUrl = link.getOriginalUrl();
        // D-C1-6: utm fields alone are a valid update; merge into the provided or the
        // stored URL and validate the MERGED result like create. Legacy-dirty rows with
        // neither a new URL nor utm keep today's no-revalidation behavior.
        if (hasUrl || !utm.isEmpty()) {
            effectiveUrl = mergeUtm(hasUrl ? request.getOriginalUrl().trim() : effectiveUrl, utm);
            LinkValidations.requireHttpUrl(effectiveUrl);
            rejectBlockedTarget(effectiveUrl);
            link.setOriginalUrl(effectiveUrl);
        }
        if (request.getTitle() != null) {
            link.setTitle(request.getTitle());
        }
        if (request.getExpireAt() != null) {
            link.setExpireAt(request.getExpireAt());
        }
        if (setPassword) {
            link.setAccessPasswordHash(encodeAccessPassword(request.getAccessPassword()));
        }
        link.setUpdatedAt(LocalDateTime.now());
        // Snapshot the in-flight row read BEFORE the commit so the post-write forget
        // can never kill a flight that started after it and reads the fresh row
        // (A10 audit N1).
        CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
        if (clearPassword) {
            // MyBatis-Plus's default NOT_NULL update strategy skips null fields in
            // updateById — the clear must be an explicit SET NULL (D-Bw345-32).
            shortLinkMapper.update(null, new LambdaUpdateWrapper<ShortLink>()
                    .eq(ShortLink::getId, link.getId())
                    .set(ShortLink::getAccessPasswordHash, null));
            link.setAccessPasswordHash(null);
        }
        shortLinkMapper.updateById(link);
        singleFlight.forget(code, priorFlight);
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
        CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
        shortLinkMapper.updateById(link);
        singleFlight.forget(code, priorFlight);
        evictCache(code);
        scheduleStaleClear(code);
    }

    /** Logical delete: the code stays reserved (uk_code) and cannot be reused. */
    public void delete(String code) {
        ShortLink link = getOwnedLink(code);
        CompletableFuture<ShortLink> priorFlight = singleFlight.peek(code);
        shortLinkMapper.deleteById(link.getId());
        singleFlight.forget(code, priorFlight);
        evictCache(code);
        scheduleStaleClear(code);
    }

    /**
     * GET navigation: shared gate chain, then B2 classification — prefetch/preview-bot
     * jumps get the same 302 with nothing recorded (HEAD contract), everything else
     * records the click synchronously and optionally publishes to MQ when enabled.
     */
    public RedirectOutcome resolveForGet(String code, HttpServletRequest request) {
        RedirectOutcome outcome = resolveTarget(code, request, true);
        if (outcome instanceof RedirectOutcome.Redirect redirect) {
            Optional<ClickFilter.SkipReason> skip = clickFilter.classify(request);
            if (skip.isPresent()) {
                meterRegistry.counter("linkforge.click.skipped", "reason", skip.get().tag()).increment();
                return redirect;
            }
            clickRecordService.recordClick(redirect.link(), request);
            meterRegistry.counter("linkforge.redirects").increment();
        }
        return outcome;
    }

    /**
     * HEAD probe: same gate chain and verdicts, but never classifies, never records,
     * and never collapses the warn gate (?lf_continue is a GET affordance).
     */
    public RedirectOutcome resolveForProbe(String code, HttpServletRequest request) {
        return resolveTarget(code, request, false);
    }

    /**
     * Shared gate chain for GET and HEAD: redirect rate-limit bucket, Bloom gate
     * (never-created codes 404 pre-DB), cache read, row state/expiry check, cache
     * warm-up, one DB read for both callers — then the B9 target gate (D-Bw345-51:
     * BLOCK before everything, WARN after it). 404/410/429 stay exceptions.
     */
    private RedirectOutcome resolveTarget(String code, HttpServletRequest request,
                                          boolean warnContinueAllowed) {
        LinkForgeProperties.RateLimit rl = properties.getRateLimit();
        RateLimitDecision decision = rateLimiter.acquire(
                RateLimitKeys.sw(rl.getKeyPrefix(), "redirect:ip:" + ClientIps.resolve(request, rl.isTrustXff())),
                rl.getRedirectMax(), rl.getRedirectWindowSeconds());
        if (!decision.allowed()) {
            meterRegistry.counter("linkforge.ratelimit.rejected", "entry", "redirect").increment();
            throw new RateLimitedException(decision.retryAfterSeconds());
        }

        // A9: a negative Bloom answer is authoritative (code never created) — 404
        // without touching MySQL. False positives and disabled/broken states fall
        // through to the cache+DB path below, which stays the correctness arbiter.
        if (!codeBloom.mightContain(code)) {
            meterRegistry.counter("linkforge.bloom.rejected").increment();
            throw new BizException(404, "short link not found");
        }

        // B7 negative cache: the DB has confirmed this code absent recently. Only
        // confirmed-absent codes live here — never disabled (404) or expired (410),
        // whose rows still exist and must take effect again immediately.
        if (absentKnown(code)) {
            meterRegistry.counter("linkforge.cache.absent.hit").increment();
            throw new BizException(404, "short link not found");
        }

        String cacheKey = CACHE_KEY_PREFIX + code;
        // B8 L1 (URL strings only, never the row) in front of the Redis L2; a hit
        // saves a Redis round-trip but the row below is still read per request.
        String cached = urlLocalCache.getIfPresent(code);
        boolean servedFromLocal = cached != null && !cached.isBlank();
        if (!servedFromLocal) {
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            // B1: the Redis L2 round-trip had no metric — dashboards need the hit rate
            meterRegistry.counter("linkforge.cache.l2", "result",
                    cached != null && !cached.isBlank() ? "hit" : "miss").increment();
        }
        // A10: concurrent same-code reads merge into one in-flight DB read (whether
        // or not Redis hit). The verdicts below still run per request on the row the
        // caller got: a follower may see a row at most one query latency older than
        // its arrival (ms, well under the existing 1s double-delete window).
        ShortLink link = singleFlight.run(code, () -> rowLoader.load(code));
        if (link == null) {
            // B7: DB says the code does not exist (Bloom false positive, logical
            // delete, or Bloom off) — remember for a short jittered TTL.
            markAbsent(code);
            throw new BizException(404, "short link not found");
        }
        if (link.getStatus() == null || link.getStatus() != 1) {
            throw new BizException(404, "short link not found");
        }
        if (link.getExpireAt() != null && link.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(410, "short link expired");
        }

        // The row is authoritative for "is this target broken": a stale L1/L2 entry
        // must never mask a dirty/poisoned row (legacy-dirty contract, LinkLifecycleIT).
        if (!LinkValidations.isRenderableUrl(link.getOriginalUrl())) {
            throw new BizException(410, "short link target is invalid");
        }
        String originalUrl = cached != null && !cached.isBlank() ? cached : link.getOriginalUrl();
        if (!LinkValidations.isRenderableUrl(originalUrl)) {
            throw new BizException(410, "short link target is invalid");
        }
        // Bw1-N1 (moved after BOTH validations per Bw2-N1): a Redis-only hit backfills
        // the L1 so a hot code stops paying the Redis round-trip after the 10s L1 TTL —
        // and only values that passed the row gate AND the renderable check land in L1.
        if (!servedFromLocal && cached != null && !cached.isBlank()) {
            urlLocalCache.put(code, cached);
        }
        if (cached == null || cached.isBlank()) {
            cacheUrl(code, originalUrl);
        }

        // B9 gate on the ROW's target (row-authoritative, D-Bw345-21): a stale cache
        // entry must never mask what the DB says the link points at today.
        TargetPolicy.Verdict verdict = phishingPolicy.evaluate(link.getOriginalUrl(), false);
        if (verdict.action() == TargetPolicy.Action.BLOCK) {
            meterRegistry.counter("linkforge.phishing.gate", "action", "block", "stage", "redirect").increment();
            return new RedirectOutcome.Blocked();
        }
        // B3 password gate, row-authoritative: set/clear/change applies on the next
        // request, no cache TTL involved. Read-only on the shared flight row.
        if (link.getAccessPasswordHash() != null
                && !linkAccessTokens.validate(accessCookie(request, code), link.getId(), code, link.getAccessPasswordHash())) {
            meterRegistry.counter("linkforge.link.locked").increment();
            return new RedirectOutcome.Locked(link);
        }
        if (verdict.action() == TargetPolicy.Action.WARN) {
            if (warnContinueAllowed && "1".equals(request.getParameter("lf_continue"))) {
                meterRegistry.counter("linkforge.phishing.continue").increment();
            } else {
                meterRegistry.counter("linkforge.phishing.gate", "action", "warn", "stage", "redirect").increment();
                return new RedirectOutcome.Warn(code, link.getOriginalUrl(),
                        TargetPolicy.asciiHost(link.getOriginalUrl()));
            }
        }
        return new RedirectOutcome.Redirect(link, originalUrl);
    }

    /** B3: the unlock cookie for this code, or null when the request carries none. */
    private String accessCookie(HttpServletRequest request, String code) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = linkAccessTokens.cookieName(code);
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record UnlockResult(ShortLink link, String cookieValue) {
        public boolean unlocked() {
            return cookieValue != null;
        }

        public boolean protectedLink() {
            return link != null && link.getAccessPasswordHash() != null;
        }
    }

    /**
     * B3 unlock: Bloom/absent/row-state mirror the redirect path, then the two-tier
     * rate limit fires BEFORE BCrypt — brute force must never buy BCrypt CPU
     * (D-Bw345-34). Success issues the signed cookie; the caller renders the response.
     */
    public UnlockResult unlock(String code, String password, HttpServletRequest request) {
        if (!codeBloom.mightContain(code) || absentKnown(code)) {
            throw new BizException(404, "short link not found");
        }
        ShortLink link = singleFlight.run(code, () -> rowLoader.load(code));
        if (link == null || link.getStatus() == null || link.getStatus() != 1) {
            throw new BizException(404, "short link not found");
        }
        if (link.getExpireAt() != null && link.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(410, "short link expired");
        }
        LinkForgeProperties.LinkAccess cfg = properties.getLinkAccess();
        LinkForgeProperties.RateLimit rl = properties.getRateLimit();
        String ip = ClientIps.resolve(request, rl.isTrustXff());
        RateLimitDecision perIp = rateLimiter.acquire(
                RateLimitKeys.sw(rl.getKeyPrefix(), "unlock:code:" + code + ":ip:" + ip),
                cfg.getUnlockIpMax(), cfg.getUnlockWindowSeconds());
        if (!perIp.allowed()) {
            meterRegistry.counter("linkforge.link.unlock", "result", "rate_limited").increment();
            throw new RateLimitedException(perIp.retryAfterSeconds());
        }
        RateLimitDecision perCode = rateLimiter.acquire(
                RateLimitKeys.sw(rl.getKeyPrefix(), "unlock:code:" + code),
                cfg.getUnlockCodeMax(), cfg.getUnlockWindowSeconds());
        if (!perCode.allowed()) {
            meterRegistry.counter("linkforge.link.unlock", "result", "rate_limited").increment();
            throw new RateLimitedException(perCode.retryAfterSeconds());
        }
        if (link.getAccessPasswordHash() == null) {
            return new UnlockResult(link, null);
        }
        if (password == null || password.isBlank()
                || !passwordEncoder.matches(password, link.getAccessPasswordHash())) {
            meterRegistry.counter("linkforge.link.unlock", "result", "bad_password").increment();
            return new UnlockResult(link, null);
        }
        meterRegistry.counter("linkforge.link.unlock", "result", "ok").increment();
        return new UnlockResult(link,
                linkAccessTokens.issue(link.getId(), code, link.getAccessPasswordHash()));
    }

    /**
     * B9 create/update gate: a BLOCK verdict rejects with a 400 whose message never
     * echoes the listed domain (D-Bw345-21); a WARN verdict only counts.
     */
    private void rejectBlockedTarget(String url) {
        TargetPolicy.Verdict verdict = phishingPolicy.evaluate(url, true);
        if (verdict.action() == TargetPolicy.Action.BLOCK) {
            meterRegistry.counter("linkforge.phishing.gate", "action", "block", "stage", "create").increment();
            throw new BizException("target domain is not allowed by policy");
        }
        if (verdict.action() == TargetPolicy.Action.WARN) {
            meterRegistry.counter("linkforge.phishing.gate", "action", "warn", "stage", "create").increment();
        }
    }

    /** B3: BCrypt-encode an access password; 4-64 chars when present, null stays null. */
    private String encodeAccessPassword(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        String trimmed = password.trim();
        if (trimmed.length() < 4 || trimmed.length() > 64) {
            throw new BizException("accessPassword must be 4-64 characters");
        }
        return passwordEncoder.encode(trimmed);
    }

    /** D-C1-6/7: write-time merge; a merge past the 2048 column cap is a 400, never a DB error. */
    private static String mergeUtm(String url, Map<String, String> utm) {
        String merged = UtmMerger.merge(url, utm);
        if (merged == null) {
            throw new BizException(400, "merged originalUrl exceeds 2048");
        }
        return merged;
    }

    private static void rejectSmuggledAttribution(Map<String, Object> unknown) {
        for (String name : unknown.keySet()) {
            if (UTM_PREFIX.matcher(name).lookingAt()
                    || CLICK_ATTRIBUTION_IDS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new BizException(400, "unknown attribution parameter: " + name);
            }
        }
    }

    /** C1 wave B: material opened by share signature bypasses ownership (the signature
     * IS the owner's consent) but the code must still exist — 404 without an identity. */
    public ShortLink requireExisting(String code) {
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        if (link == null) {
            throw new BizException(404, "short link not found");
        }
        return link;
    }

    ShortLink getOwnedLink(String code) {
        AuthUser user = requireUser();
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        if (isTenantWide(user)) {
            if (link == null || !Objects.equals(link.getTenantId(), user.getTenantId())) {
                throw new BizException(404, "short link not found");
            }
            return link;
        }
        if (link == null || link.getCreatedBy() == null || !link.getCreatedBy().equals(user.getUserId())) {
            throw new BizException(404, "short link not found");
        }
        return link;
    }

    /** D-C1-12: a TENANT-scope key sees the whole tenant; JWT and USER keys stay per-creator. */
    private static boolean isTenantWide(AuthUser user) {
        return "TENANT".equals(user.getApiKeyScope());
    }

    private static AuthUser requireUser() {
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "unauthorized");
        }
        return user;
    }

    /** B-wave1 D-Bw1-5: TTL = base + uniform [0, jitter] (upward only); jitter<=0 degenerates to base. */
    static long jitteredTtlSeconds(long baseSeconds, long jitterSeconds) {
        if (jitterSeconds <= 0) {
            return baseSeconds;
        }
        return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }

    private boolean absentKnown(String code) {
        if (properties.getShortLink().getAbsentTtlSeconds() <= 0) {
            return false;
        }
        try {
            return ABSENT_SENTINEL.equals(stringRedisTemplate.opsForValue().get(ABSENT_KEY_PREFIX + code));
        } catch (Exception ex) {
            // fail-open: a broken probe must not break redirects (Bw1 D-Bw1-12)
            log.warn("absent probe failed for {}: {}", code, ex.getMessage());
            return false;
        }
    }

    private void markAbsent(String code) {
        if (properties.getShortLink().getAbsentTtlSeconds() <= 0) {
            return;
        }
        long ttl = jitteredTtlSeconds(properties.getShortLink().getAbsentTtlSeconds(),
                properties.getShortLink().getAbsentTtlJitterSeconds());
        try {
            stringRedisTemplate.opsForValue().set(ABSENT_KEY_PREFIX + code, ABSENT_SENTINEL, ttl, TimeUnit.SECONDS);
            meterRegistry.counter("linkforge.cache.absent.put").increment();
        } catch (Exception ex) {
            log.warn("absent write failed for {}: {}", code, ex.getMessage());
        }
    }

    private void clearAbsent(String code) {
        if (properties.getShortLink().getAbsentTtlSeconds() <= 0) {
            return;
        }
        try {
            stringRedisTemplate.delete(ABSENT_KEY_PREFIX + code);
        } catch (Exception ex) {
            log.warn("absent clear failed for {}: {}", code, ex.getMessage());
        }
    }

    private void cacheUrl(String code, String url) {
        long ttl = jitteredTtlSeconds(properties.getShortLink().getCacheTtlSeconds(),
                properties.getShortLink().getCacheTtlJitterSeconds());
        urlLocalCache.put(code, url);
        stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + code, url, ttl, TimeUnit.SECONDS);
    }

    private void evictCache(String code) {
        urlLocalCache.invalidate(code);
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
                link.getStatus(), link.getExpireAt(), link.getCreatedAt(), link.getUpdatedAt(),
                link.getAccessPasswordHash() != null);
    }
}

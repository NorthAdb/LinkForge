package com.linkforge.link.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.exception.BizException;
import com.linkforge.common.result.PageResponse;
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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private static final String CACHE_KEY_PREFIX = "linkforge:code:";
    private static final char[] CODE_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShortLinkMapper shortLinkMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LinkForgeProperties properties;
    private final ClickRecordService clickRecordService;

    @Transactional
    public CreateShortLinkResponse create(CreateShortLinkRequest request) {
        AuthUser user = requireUser();
        String url = request.getOriginalUrl().trim();
        requireHttpUrl(url);
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BizException("expireAt must be in the future");
        }

        String code = allocateCode();
        ShortLink link = new ShortLink();
        link.setTenantId(user.getTenantId());
        link.setCode(code);
        link.setOriginalUrl(url);
        link.setTitle(request.getTitle());
        link.setStatus(1);
        link.setExpireAt(request.getExpireAt());
        link.setCreatedBy(user.getUserId());
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.insert(link);

        cacheUrl(code, url);
        return new CreateShortLinkResponse(link.getId(), code, url, "/r/" + code);
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

    @Transactional
    public ShortLinkDetail update(String code, UpdateShortLinkRequest request) {
        boolean hasUrl = request.getOriginalUrl() != null && !request.getOriginalUrl().isBlank();
        if (!hasUrl && request.getTitle() == null) {
            throw new BizException("nothing to update");
        }
        ShortLink link = getOwnedLink(code);
        if (hasUrl) {
            String url = request.getOriginalUrl().trim();
            requireHttpUrl(url);
            link.setOriginalUrl(url);
        }
        if (request.getTitle() != null) {
            link.setTitle(request.getTitle());
        }
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.updateById(link);
        evictCache(code);
        return toDetail(link);
    }

    @Transactional
    public void disable(String code) {
        ShortLink link = getOwnedLink(code);
        link.setStatus(0);
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.updateById(link);
        evictCache(code);
    }

    /**
     * Resolve redirect URL: Redis first, then MySQL. The DB row is always read so a
     * disabled or expired link is rejected even while a stale cache entry exists.
     * Records the click synchronously; optionally publishes to MQ when enabled.
     */
    public String resolveAndRecord(String code, HttpServletRequest request) {
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

    private String allocateCode() {
        int len = properties.getShortLink().getCodeLength();
        for (int i = 0; i < 16; i++) {
            String code = randomCode(len);
            Long count = shortLinkMapper.selectCount(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
            if (count == null || count == 0) {
                return code;
            }
        }
        throw new BizException("failed to allocate short code");
    }

    private static String randomCode(int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(buf);
    }

    private static ShortLinkDetail toDetail(ShortLink link) {
        return new ShortLinkDetail(link.getId(), link.getCode(), link.getOriginalUrl(), link.getTitle(),
                link.getStatus(), link.getExpireAt(), link.getCreatedAt(), link.getUpdatedAt());
    }
}

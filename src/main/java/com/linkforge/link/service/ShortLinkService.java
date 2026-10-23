package com.linkforge.link.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.dto.CreateShortLinkRequest;
import com.linkforge.link.dto.CreateShortLinkResponse;
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
        AuthUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "unauthorized");
        }
        String url = request.getOriginalUrl().trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException("originalUrl must start with http:// or https://");
        }

        String code = allocateCode();
        ShortLink link = new ShortLink();
        link.setTenantId(user.getTenantId());
        link.setCode(code);
        link.setOriginalUrl(url);
        link.setTitle(request.getTitle());
        link.setStatus(1);
        link.setCreatedBy(user.getUserId());
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());
        shortLinkMapper.insert(link);

        cacheUrl(code, url);
        return new CreateShortLinkResponse(link.getId(), code, url, "/r/" + code);
    }

    /**
     * Resolve redirect URL: Redis first, then MySQL. Records click synchronously (MVP);
     * optionally publishes to MQ when async-click-enabled=true.
     */
    public String resolveAndRecord(String code, HttpServletRequest request) {
        String cacheKey = CACHE_KEY_PREFIX + code;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        ShortLink link = null;
        String originalUrl;

        if (cached != null && !cached.isBlank()) {
            originalUrl = cached;
            link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
        } else {
            link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
            if (link == null || link.getStatus() == null || link.getStatus() != 1) {
                throw new BizException(404, "short link not found");
            }
            if (link.getExpireAt() != null && link.getExpireAt().isBefore(LocalDateTime.now())) {
                throw new BizException(410, "short link expired");
            }
            originalUrl = link.getOriginalUrl();
            cacheUrl(code, originalUrl);
        }

        if (link == null) {
            throw new BizException(404, "short link not found");
        }
        if (link.getExpireAt() != null && link.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(410, "short link expired");
        }

        clickRecordService.recordClick(link, request);
        return originalUrl;
    }

    private void cacheUrl(String code, String url) {
        long ttl = properties.getShortLink().getCacheTtlSeconds();
        stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + code, url, ttl, TimeUnit.SECONDS);
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
}

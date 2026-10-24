package com.linkforge.link.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * B-wave1 B8: process-local L1 for hot-code URL strings, in front of the Redis L2.
 * Caches NOTHING but the URL (same shape as the Redis value) and a hit never skips
 * the singleflight DB row read — status/expiry stay DB-authoritative (D-Bw1-6).
 * Instance-local by design: another instance's writes are not broadcast; the stale
 * window is this TTL (default 10s), documented in the README. Disabled at runtime
 * = every read misses and writes are skipped, i.e. the exact A10 behaviour.
 */
@Slf4j
@Component
public class UrlLocalCache {

    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final Cache<String, String> cache;

    public UrlLocalCache(LinkForgeProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        LinkForgeProperties.L1 cfg = properties.getL1();
        this.cache = Caffeine.newBuilder()
                .maximumSize(cfg.getMaximumSize())
                .expireAfterWrite(Duration.ofSeconds(cfg.getTtlSeconds()))
                .build();
        log.info("url L1 cache built (enabled={}, maximumSize={}, ttlSeconds={})",
                cfg.isEnabled(), cfg.getMaximumSize(), cfg.getTtlSeconds());
    }

    public String getIfPresent(String code) {
        if (!properties.getL1().isEnabled()) {
            return null;
        }
        String url = cache.getIfPresent(code);
        meterRegistry.counter(url != null ? "linkforge.l1.hit" : "linkforge.l1.miss").increment();
        return url;
    }

    public void put(String code, String url) {
        if (properties.getL1().isEnabled()) {
            cache.put(code, url);
        }
    }

    public void invalidate(String code) {
        if (properties.getL1().isEnabled()) {
            cache.invalidate(code);
        }
    }
}

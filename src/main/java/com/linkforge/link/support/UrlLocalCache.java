package com.linkforge.link.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * B-wave1 B8: process-local L1 for hot-code URL strings, in front of the Redis L2.
 * Caches NOTHING but the URL (same shape as the Redis value) and a hit never skips
 * the singleflight DB row read — status/expiry stay DB-authoritative (D-Bw1-6).
 * Stale window across instances is normally this TTL (default 10s), tightened by
 * the wave C peer invalidate broadcast (subscribe below). Disabled at runtime
 * = every read misses and writes are skipped, i.e. the exact A10 behaviour.
 */
@Slf4j
@Component
public class UrlLocalCache {

    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final RedisMessageListenerContainer container;
    private final Cache<String, String> cache;

    public UrlLocalCache(LinkForgeProperties properties, MeterRegistry meterRegistry,
                         RedisMessageListenerContainer container) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.container = container;
        LinkForgeProperties.L1 cfg = properties.getL1();
        this.cache = Caffeine.newBuilder()
                .maximumSize(cfg.getMaximumSize())
                .expireAfterWrite(Duration.ofSeconds(cfg.getTtlSeconds()))
                .build();
        log.info("url L1 cache built (enabled={}, maximumSize={}, ttlSeconds={}); "
                        + "process-local, not shared across instances",
                cfg.isEnabled(), cfg.getMaximumSize(), cfg.getTtlSeconds());
    }

    /** C2 wave C (D-C2-17): peer invalidates drop ONLY this local L1 slot. */
    @PostConstruct
    void subscribe() {
        if (!properties.isCacheBusEnabled()) {
            return;
        }
        container.addMessageListener((message, pattern) -> applyRemoteInvalidate(message),
                new ChannelTopic(CacheBus.L1_INVALIDATE_CHANNEL));
        log.info("l1 invalidate subscriber registered on {}", CacheBus.L1_INVALIDATE_CHANNEL);
    }

    /** Listener-thread work: parse, skip malformed/own-origin, then L1 slot only — never the Redis L2. */
    private void applyRemoteInvalidate(Message message) {
        CacheBus.Put put = CacheBus.parsePut(new String(message.getBody(), StandardCharsets.UTF_8));
        if (put == null) {
            meterRegistry.counter("linkforge.l1.invalidate.malformed").increment();
            return;
        }
        // D-C2-18: own origin means this instance just wrote the fresh URL through
        // (cacheUrl at the write); wiping it would self-harm. Peers apply, self skips.
        if (CacheBus.INSTANCE_ID.equals(put.origin())) {
            return;
        }
        invalidate(put.code());
        meterRegistry.counter("linkforge.l1.invalidate.remote").increment();
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

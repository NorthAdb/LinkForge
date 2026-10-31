package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * C2 wave A (D-C2-3..8): the Redis pub/sub bus shared by the Bloom put broadcast
 * (wave A) and the L1 invalidate broadcast (wave C). Channels and the payload
 * parser live here so the {@code <code>|<origin>} wire format stays pinned in one
 * place. Broadcasts are best-effort: a lost message leaves peers stale until their
 * next full load/rebuild, so failures never fail the triggering write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheBus {

    public static final String BLOOM_PUT_CHANNEL = "linkforge:bloom:put";
    /** Reserved for wave C: resolution-changing writes invalidate peer L1 caches. */
    public static final String L1_INVALIDATE_CHANNEL = "linkforge:l1:invalidate";
    /** Per-JVM identity carried in every payload so wave C receivers can skip their own messages. */
    public static final String INSTANCE_ID = "i-" + UUID.randomUUID();

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final LinkForgeProperties properties;

    /**
     * Broadcasts a freshly created code to peer Bloom filters (D-C2-6). Silent
     * no-op with the cache bus disabled; failure is swallowed (log + error meter)
     * so creation always succeeds (fail-open).
     */
    public void publishBloomPut(String code) {
        if (!properties.isCacheBusEnabled()) {
            return;
        }
        try {
            redis.convertAndSend(BLOOM_PUT_CHANNEL, code + "|" + INSTANCE_ID);
            meterRegistry.counter("linkforge.bloom.publish", "result", "ok").increment();
        } catch (Exception ex) {
            meterRegistry.counter("linkforge.bloom.publish", "result", "error").increment();
            log.warn("bloom publish failed; peers stay stale until their next load: {}", ex.getMessage());
        }
    }

    /**
     * Broadcasts that a resolution-changing write committed for the code (wave C,
     * D-C2-16); peers drop their local L1 slot. Same fail-open contract as
     * publishBloomPut: a lost publish leaves peers stale up to their 10s L1 TTL.
     */
    public void publishL1Invalidate(String code) {
        if (!properties.isCacheBusEnabled()) {
            return;
        }
        try {
            redis.convertAndSend(L1_INVALIDATE_CHANNEL, code + "|" + INSTANCE_ID);
            meterRegistry.counter("linkforge.l1.invalidate.publish", "result", "ok").increment();
        } catch (Exception ex) {
            meterRegistry.counter("linkforge.l1.invalidate.publish", "result", "error").increment();
            log.warn("l1 invalidate publish failed; peers stay stale up to their L1 TTL: {}", ex.getMessage());
        }
    }

    /**
     * Parses the {@code <code>|<origin>} payload; null when malformed. Callers count
     * and drop nulls — a bad payload must never throw on the listener thread.
     */
    public static Put parsePut(String payload) {
        String[] parts = payload.split("\\|", 2);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return new Put(parts[0], parts[1]);
    }

    /** One broadcast message: the code plus the publishing instance's identity. */
    public record Put(String code, String origin) {
    }
}

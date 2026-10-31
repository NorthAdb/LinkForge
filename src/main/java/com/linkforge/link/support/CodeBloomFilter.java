package com.linkforge.link.support;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.ShortLinkMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A9: process-local Guava Bloom over short codes, guarding the redirect hot path
 * against never-created codes (cache penetration). This filter holds ONLY codes —
 * never status/expire (those stay DB-authoritative) and never long URLs (the
 * dedup-Bloom story is a different feature; the two must not share a structure).
 *
 * Semantics: {@code mightContain == false} is authoritative (the code was not in
 * the DB at load time and was never created here), so the caller may 404 before
 * MySQL. {@code true} includes Guava's configured-fpp false positives, which
 * simply fall through to the normal cache+DB resolution — correctness unaffected.
 * Deleted codes stay positive (classic Bloom cannot retract): they cost one DB
 * miss and 404 as before. Still a process-local filter across instances (no
 * RedisBloom): peer alignment is the CacheBus put broadcast plus the scheduled /
 * ADMIN-triggered rebuild, with the README recording the residual gap honestly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeBloomFilter {

    private final ShortLinkMapper shortLinkMapper;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final RedisMessageListenerContainer container;

    private volatile BloomFilter<String> filter;
    private volatile boolean ready;

    /** D-C2-13: one rebuild at a time per process; overlapping triggers read busy. */
    private final AtomicBoolean rebuilding = new AtomicBoolean();

    /** C2 wave A (D-C2-5): peer puts teach this filter; own messages are applied too. */
    @PostConstruct
    void subscribe() {
        if (!properties.isCacheBusEnabled()) {
            return;
        }
        // Registered pre-load (D-C2-5): the container is a SmartLifecycle that subscribes at context start, strictly before the ApplicationReadyEvent load, so no put can land between load and subscribe (that gap would stay invisible until rebuild).
        container.addMessageListener((message, pattern) -> applyRemotePut(message),
                new ChannelTopic(CacheBus.BLOOM_PUT_CHANNEL));
        log.info("bloom put broadcast subscriber registered on {}", CacheBus.BLOOM_PUT_CHANNEL);
    }

    /** Listener-thread work only: one parse + one idempotent put, no executor hop. */
    private void applyRemotePut(Message message) {
        CacheBus.Put put = CacheBus.parsePut(new String(message.getBody(), StandardCharsets.UTF_8));
        if (put == null) {
            meterRegistry.counter("linkforge.bloom.subscribe.malformed").increment();
            return;
        }
        // Own messages are applied too (D-C2-7): Guava put is idempotent and an origin skip only adds a way to lose a put.
        put(put.code());
        meterRegistry.counter("linkforge.bloom.subscribe.put").increment();
    }

    /** Full seed from the DB once the app is up; a failed load must never take redirects down. */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        if (!properties.getBloom().isEnabled()) {
            log.info("bloom disabled by config; redirects stay on the cache+DB path "
                    + "(the filter is process-local and is not shared across instances)");
            return;
        }
        try {
            LinkForgeProperties.Bloom cfg = properties.getBloom();
            BloomFilter<String> fresh = BloomFilter.create(
                    Funnels.stringFunnel(StandardCharsets.UTF_8), cfg.getExpectedInsertions(), cfg.getFpp());
            // Publish the (still fail-open) filter before seeding: a create landing in
            // the seeding window calls put() straight into `fresh` (Guava bit array is
            // concurrency-safe) instead of being dropped and staying invisible until
            // the next restart (A9 audit S1).
            filter = fresh;
            List<String> codes = shortLinkMapper.selectAllCodes();
            for (String code : codes) {
                fresh.put(code);
            }
            ready = true;
            meterRegistry.counter("linkforge.bloom.loaded").increment();
            log.info("bloom seeded with {} codes (expectedInsertions={}, fpp={}, approxSize={}); "
                            + "process-local, not shared across instances",
                    codes.size(), cfg.getExpectedInsertions(), cfg.getFpp(), fresh.approximateElementCount());
        } catch (Exception ex) {
            // No half-seeded filter may linger: back to the fail-open state
            // (filter==null, ready==false) so every probe keeps going to the DB.
            filter = null;
            ready = false;
            meterRegistry.counter("linkforge.bloom.loadfailed").increment();
            log.warn("bloom load failed; fail-open on the cache+DB path: {}", ex.getMessage(), ex);
        }
    }

    /** C2 wave B (D-C2-9): scheduled full rebuild; a disabled flag returns immediately. */
    @Scheduled(fixedDelayString = "${linkforge.bloom.rebuild-interval-ms:86400000}")
    public void scheduledRebuild() {
        if (!properties.getBloom().isRebuildEnabled()) {
            return;
        }
        rebuild(false);
    }

    /** Manual ADMIN trigger (D-C2-12): rebuilds THIS process's filter, returns the seeded count. */
    public long rebuildManual() {
        return rebuild(true);
    }

    /**
     * D-C2-10 bypass-then-atomic-swap, mirroring load(): publish the fresh filter first
     * so concurrent creates put straight into it, then seed from selectAllCodes(); the
     * swap and the failure restore are single volatile writes.
     */
    private long rebuild(boolean manual) {
        if (!properties.getBloom().isEnabled()) {
            // load() leaves the filter absent when disabled; rebuilding would publish one
            // (and flip isReady) behind a feature that is off.
            return 0;
        }
        if (!rebuilding.compareAndSet(false, true)) {
            meterRegistry.counter("linkforge.bloom.rebuild", "result", "busy").increment();
            if (manual) {
                throw new BizException(409, "bloom rebuild already in progress");
            }
            return 0;
        }
        try {
            LinkForgeProperties.Bloom cfg = properties.getBloom();
            BloomFilter<String> fresh = BloomFilter.create(
                    Funnels.stringFunnel(StandardCharsets.UTF_8), cfg.getExpectedInsertions(), cfg.getFpp());
            // Publish before seeding (same A9 audit S1 reason as load()): a create landing
            // in the seeding window puts straight into `fresh` instead of being lost.
            BloomFilter<String> previous = filter;
            filter = fresh;
            try {
                List<String> codes = shortLinkMapper.selectAllCodes();
                for (String code : codes) {
                    fresh.put(code);
                }
                ready = true;
                meterRegistry.counter("linkforge.bloom.rebuild", "result", "ok").increment();
                log.info("bloom rebuilt with {} codes (expectedInsertions={}, fpp={}, approxSize={})",
                        codes.size(), cfg.getExpectedInsertions(), cfg.getFpp(), fresh.approximateElementCount());
                return codes.size();
            } catch (Exception ex) {
                // Restore the pre-rebuild reference; never downgrade a loaded filter to
                // fail-open (ready stays true unless the old filter was already absent).
                filter = previous;
                ready = previous != null;
                meterRegistry.counter("linkforge.bloom.rebuild", "result", "error").increment();
                log.warn("bloom rebuild failed; previous filter restored: {}", ex.getMessage(), ex);
                if (manual) {
                    throw new BizException(500, "bloom rebuild failed: " + ex.getMessage());
                }
                return 0;
            }
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * False when the code was definitely never created (caller may 404 pre-DB).
     * Disabled, not-yet-loaded or failed-load always answers true (fail-open).
     */
    public boolean mightContain(String code) {
        BloomFilter<String> current = filter;
        if (!properties.getBloom().isEnabled() || !ready || current == null) {
            return true;
        }
        return current.mightContain(code);
    }

    /** Records a freshly created code; a no-op while the filter is absent (fail-open state). */
    public void put(String code) {
        BloomFilter<String> current = filter;
        if (current != null) {
            current.put(code);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public long approximateElementCount() {
        BloomFilter<String> current = filter;
        return current == null ? 0 : current.approximateElementCount();
    }
}

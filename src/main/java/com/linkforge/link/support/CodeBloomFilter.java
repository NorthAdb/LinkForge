package com.linkforge.link.support;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.ShortLinkMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * miss and 404 as before. Multi-instance deployments each load their own copy
 * (demo posture); RedisBloom is the shared-filter follow-up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeBloomFilter {

    private final ShortLinkMapper shortLinkMapper;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;

    private volatile BloomFilter<String> filter;
    private volatile boolean ready;

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

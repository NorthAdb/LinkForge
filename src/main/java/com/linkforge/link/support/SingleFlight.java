package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A10: process-local singleflight — concurrent same-key calls share one in-flight
 * loader execution. Used by the redirect hot path so N simultaneous GETs of one
 * code cost ONE DB row read instead of N.
 *
 * Shape (deliberate):
 * - The leader executes the loader synchronously on its OWN request thread — no
 *   extra pool, no async hand-off. Followers block on the leader's future up to
 *   wait-timeout-ms, then do their own read (fail-open, counted as timeout).
 * - {@code putIfAbsent}, never {@code computeIfAbsent}: a blocking loader inside
 *   computeIfAbsent would hold the CHM bin lock and stall unrelated keys.
 * - Entries are removed as soon as the flight settles (remove(key, future) two-arg,
 *   so a late arrival can never be dropped by the finishing leader). This is a
 *   de-duplicator, NOT a result cache: nothing survives the flight, no TTL, no
 *   last-value memory. Null results (bloom false-positives, deleted codes) are
 *   shared within the flight only — no negative caching (B7 is out of scope).
 * - {@code forget(key)} lets write paths (update/disable/delete) drop an in-flight
 *   read so requests arriving after the commit re-lead and read the fresh row.
 *
 * Failure semantics: a loader exception is handed to every follower already
 * waiting (same as if each had read and failed itself) and the entry is removed,
 * so the next caller re-leads. No automatic retry — a DB outage must not be
 * amplified. Multi-instance deployments merge per process only; a distributed
 * lock would just serialise the reads without removing them (README).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleFlight<K, V> {

    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * Runs {@code loader} for this key, joining an already in-flight execution for
     * the same key when there is one. Disabled config = plain per-call execution.
     */
    public V run(K key, Supplier<V> loader) {
        LinkForgeProperties.SingleFlight cfg = properties.getSingleFlight();
        if (!cfg.isEnabled()) {
            return loader.get();
        }
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing == null) {
            // leader: counts every lead attempt, successful or not
            meterRegistry.counter("linkforge.singleflight.loads").increment();
            try {
                V value = loader.get();
                mine.complete(value);
                return value;
            } catch (RuntimeException ex) {
                mine.completeExceptionally(ex);
                throw ex;
            } finally {
                inFlight.remove(key, mine);
            }
        }
        // follower: share the in-flight read, degrade to a self-read on timeout
        try {
            V value = existing.get(cfg.getWaitTimeoutMs(), TimeUnit.MILLISECONDS);
            meterRegistry.counter("linkforge.singleflight.shared").increment();
            return value;
        } catch (TimeoutException ex) {
            meterRegistry.counter("linkforge.singleflight.timeout").increment();
            return loader.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("linkforge.singleflight.timeout").increment();
            return loader.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Drops any in-flight entry for the key so the next caller re-leads (write paths). */
    public void forget(K key) {
        inFlight.remove(key);
    }
}

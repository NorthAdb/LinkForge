package com.linkforge.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Sliding window log over a Redis ZSET (Lua, clocked by Redis TIME) — not a fixed
 * window and not a token bucket. Only allowed requests enter the window, so rejected
 * callers cannot prolong their own lockout, and a full window reports a real
 * Retry-After (the oldest live entry). Redis errors degrade open (allowed); every
 * fail-open is counted so a silently disabled limiter stays visible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();

    static {
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("redis/sliding_window.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;

    /** Sliding window verdict; use this when the caller wants a Retry-After. */
    @SuppressWarnings("unchecked")
    public RateLimitDecision acquire(String key, int max, int windowSeconds) {
        if (max <= 0) {
            return new RateLimitDecision(false, windowSeconds, 0);
        }
        List<Long> result;
        try {
            result = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
                    String.valueOf(max), String.valueOf(windowSeconds * 1000L),
                    String.valueOf(ThreadLocalRandom.current().nextLong()));
        } catch (Exception ex) {
            return failOpen(key, "error", ex);
        }
        if (result == null || result.size() < 3 || result.get(0) == null) {
            return failOpen(key, "null", null);
        }
        boolean allowed = result.get(0) == 1L;
        long retryMs = Math.max(1L, result.get(2));
        int retryAfter = (int) Math.min(windowSeconds, (retryMs + 999) / 1000);
        return new RateLimitDecision(allowed, retryAfter, result.get(1));
    }

    /** Boolean convenience over {@link #acquire}. */
    public boolean tryAcquire(String key, int max, int windowSeconds) {
        return acquire(key, max, windowSeconds).allowed();
    }

    /**
     * Fixed-window counter (INCR + EXPIRE on the first hit), kept only for counters whose
     * semantics are a calendar reset — the API key daily quota (Asia/Shanghai midnight).
     * Degrades open on Redis errors like the sliding path; a window that fails to arm is
     * best-effort reset so a key can never lock callers out forever.
     */
    public boolean tryAcquireFixedWindow(String key, int max, int windowSeconds) {
        Long count;
        try {
            count = stringRedisTemplate.opsForValue().increment(key);
        } catch (Exception ex) {
            return true;
        }
        if (count == null) {
            return true;
        }
        if (count == 1L) {
            try {
                Boolean armed = stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
                if (!Boolean.TRUE.equals(armed)) {
                    stringRedisTemplate.delete(key);
                }
            } catch (Exception ex) {
                try {
                    stringRedisTemplate.delete(key);
                } catch (Exception ignore) {
                    log.warn("rate window {} left unarmed: {}", key, ex.getMessage());
                }
            }
        }
        return count <= max;
    }

    private RateLimitDecision failOpen(String key, String reason, Exception ex) {
        if (ex != null) {
            log.warn("rate limiter degraded open for {}: {}", key, ex.getMessage());
        }
        meterRegistry.counter("linkforge.ratelimit.failopen", "reason", reason).increment();
        return new RateLimitDecision(true, 0, 0);
    }
}

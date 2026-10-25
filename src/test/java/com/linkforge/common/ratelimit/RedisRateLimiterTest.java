package com.linkforge.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    private static final String KEY = "k";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, meters);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireOps() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @SuppressWarnings("unchecked")
    private void scriptReturns(List<Long> result) {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn((List) result);
    }

    private long failOpenCount(String reason) {
        var c = meters.find("linkforge.ratelimit.failopen").tags("reason", reason).counter();
        return c == null ? 0 : (long) c.count();
    }

    @Test
    void allowedVerdictCarriesTheCount() {
        scriptReturns(List.of(1L, 3L, 0L));

        RateLimitDecision d = limiter.acquire(KEY, 5, 60);

        assertTrue(d.allowed());
        assertEquals(3, d.count());
        verify(redis).execute(any(RedisScript.class), eq(List.of(KEY)),
                eq("5"), eq("60000"), anyString());
    }

    @Test
    void rejectionMapsRetryMsToSecondsCeiledIntoTheWindow() {
        scriptReturns(List.of(0L, 5L, 1500L));
        assertEquals(2, limiter.acquire(KEY, 5, 60).retryAfterSeconds());

        scriptReturns(List.of(0L, 5L, 1L));
        assertEquals(1, limiter.acquire(KEY, 5, 60).retryAfterSeconds());

        // a garbage-huge retry_ms is capped at the window
        scriptReturns(List.of(0L, 5L, 10_000_000L));
        assertEquals(60, limiter.acquire(KEY, 5, 60).retryAfterSeconds());

        scriptReturns(List.of(0L, 5L, 0L));
        assertEquals(1, limiter.acquire(KEY, 5, 60).retryAfterSeconds());
    }

    @Test
    void maxAtOrBelowZeroRejectsWithoutTouchingRedis() {
        RateLimitDecision d = limiter.acquire(KEY, 0, 60);

        assertFalse(d.allowed());
        assertEquals(60, d.retryAfterSeconds());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void redisFailureDegradesOpenAndCountsError() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryAcquire(KEY, 2, 60));
        assertEquals(1, failOpenCount("error"));
        assertEquals(0, failOpenCount("null"));
    }

    @Test
    void nullScriptResultDegradesOpenAndCountsNull() {
        scriptReturns(null);

        assertTrue(limiter.tryAcquire(KEY, 2, 60));
        assertEquals(1, failOpenCount("null"));
    }

    @Test
    void tryAcquireDelegatesToAcquire() {
        scriptReturns(List.of(0L, 5L, 2000L));

        assertFalse(limiter.tryAcquire(KEY, 2, 60));
    }

    @Test
    void fixedWindowCountsAndArmsOnce() {
        when(ops.increment(KEY)).thenReturn(1L, 2L, 3L);
        when(redis.expire(eq(KEY), anyLong(), any())).thenReturn(true);

        assertTrue(limiter.tryAcquireFixedWindow(KEY, 2, 86_400));
        assertTrue(limiter.tryAcquireFixedWindow(KEY, 2, 86_400));
        assertFalse(limiter.tryAcquireFixedWindow(KEY, 2, 86_400));
        verify(redis).expire(KEY, 86_400, TimeUnit.SECONDS);
    }

    @Test
    void fixedWindowDegradesOpenOnRedisFailure() {
        when(ops.increment(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryAcquireFixedWindow(KEY, 2, 86_400));
    }

    @Test
    void failedFixedWindowArmResetsKeyInsteadOfLockingForever() {
        when(ops.increment(KEY)).thenReturn(1L);
        when(redis.expire(eq(KEY), anyLong(), any())).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryAcquireFixedWindow(KEY, 2, 86_400));
        verify(redis).delete(KEY);
    }
}

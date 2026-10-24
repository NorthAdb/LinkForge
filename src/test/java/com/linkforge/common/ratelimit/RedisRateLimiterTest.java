package com.linkforge.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis);

    @BeforeEach
    void wireOps() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void firstIncrementArmsTheWindow() {
        when(ops.increment("k")).thenReturn(1L);
        when(redis.expire(eq("k"), anyLong(), any())).thenReturn(true);

        assertTrue(limiter.tryAcquire("k", 2, 60));
        verify(redis).expire("k", 60, TimeUnit.SECONDS);
    }

    @Test
    void countsBeyondMaxAreRejected() {
        when(ops.increment("k")).thenReturn(1L, 2L, 3L);
        when(redis.expire(eq("k"), anyLong(), any())).thenReturn(true);

        assertTrue(limiter.tryAcquire("k", 2, 60));
        assertTrue(limiter.tryAcquire("k", 2, 60));
        assertFalse(limiter.tryAcquire("k", 2, 60));
    }

    @Test
    void redisFailureDegradesOpen() {
        when(ops.increment("k")).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryAcquire("k", 2, 60));
        verify(redis, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void failedWindowArmResetsKeyInsteadOfLockingForever() {
        when(ops.increment("k")).thenReturn(1L);
        when(redis.expire(eq("k"), anyLong(), any())).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryAcquire("k", 2, 60));
        verify(redis).delete("k");
    }

    @Test
    void unarmedWindowReturnsFalseAndResets() {
        when(ops.increment("k")).thenReturn(1L);
        when(redis.expire(eq("k"), anyLong(), any())).thenReturn(false);

        assertTrue(limiter.tryAcquire("k", 2, 60));
        verify(redis).delete("k");
    }
}

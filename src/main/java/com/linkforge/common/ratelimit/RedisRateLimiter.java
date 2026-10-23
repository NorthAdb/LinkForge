package com.linkforge.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Fixed-window counter. If INCR fails (Redis down) the call is allowed so the
     * guarded feature degrades open instead of hard-failing.
     */
    public boolean tryAcquire(String key, int max, int windowSeconds) {
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
            stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        return count <= max;
    }
}

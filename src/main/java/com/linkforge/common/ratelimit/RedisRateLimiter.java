package com.linkforge.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Fixed-window counter. Redis errors degrade open (allowed) instead of failing the
     * guarded request. A window that fails to arm is best-effort reset so a key can
     * never lock callers out forever.
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
}

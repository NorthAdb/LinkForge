package com.linkforge.common.ratelimit;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SlidingWindowRateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private RedisRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearWindows() {
        var keys = redis.keys("linkforge:rl:it-default:sw:it-b6-*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * The 2× boundary burst a fixed window cannot absorb: one request at t0, two more
     * ~2s in (inside the 5s window), then three past the edge. A sliding window log
     * keeps exactly one slot free at that point (t0 expired, the pair at ~2s still live);
     * a fixed window resets its bucket at t0+5s and grants all three.
     */
    @Test
    void boundaryBurstDoesNotDoubleTheWindow() throws Exception {
        String key = "linkforge:rl:it-default:sw:it-b6-burst";
        long t0 = System.nanoTime();
        assertTrue(limiter.tryAcquire(key, 3, 5), "request at t0");
        TimeUnit.NANOSECONDS.sleep(TimeUnit.SECONDS.toNanos(2) - (System.nanoTime() - t0));
        assertTrue(limiter.tryAcquire(key, 3, 5), "request at ~t0+2s (1)");
        assertTrue(limiter.tryAcquire(key, 3, 5), "request at ~t0+2s (2)");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsedMs < 4500, "batch must land inside the window, took " + elapsedMs + "ms");
        TimeUnit.NANOSECONDS.sleep(TimeUnit.MILLISECONDS.toNanos(5400) - (System.nanoTime() - t0));

        int allowed = 0;
        for (int i = 0; i < 3; i++) {
            if (limiter.tryAcquire(key, 3, 5)) {
                allowed++;
            }
        }
        assertEquals(1, allowed,
                "sliding window log admits only the expired t0 slot past the boundary; "
                        + "a fixed window resets and would admit all 3");
    }
}

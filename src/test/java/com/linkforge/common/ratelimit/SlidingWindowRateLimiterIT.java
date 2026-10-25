package com.linkforge.common.ratelimit;

import com.linkforge.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SlidingWindowRateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private RedisRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void clearWindows() {
        var keys = redis.keys("linkforge:rl:it-default:*it-b6*");
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
        sleepUntil(t0, 2000);
        assertTrue(limiter.tryAcquire(key, 3, 5), "request at ~t0+2s (1)");
        assertTrue(limiter.tryAcquire(key, 3, 5), "request at ~t0+2s (2)");
        long elapsedMs = elapsedMs(t0);
        assertTrue(elapsedMs < 4500, "batch must land inside the window, took " + elapsedMs + "ms");
        sleepUntil(t0, 5400);

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

    /** Rejected calls must not enter the window, or they would extend the lockout. */
    @Test
    void rejectionsDoNotConsumeWindowSlots() throws Exception {
        String key = "linkforge:rl:it-default:sw:it-b6-reject";
        long t0 = System.nanoTime();
        assertTrue(limiter.tryAcquire(key, 1, 6), "the one allowed request");
        for (int i = 0; i < 4; i++) {
            sleepUntil(t0, 500 + i * 400);
            assertFalse(limiter.tryAcquire(key, 1, 6), "window is full, rejection " + i);
        }
        // If any rejection had been recorded, the newest (~t0+1.7s) would still block
        // at t0+6.3s; with a log of allowed requests only, the t0 entry has expired.
        sleepUntil(t0, 6300);
        assertTrue(limiter.tryAcquire(key, 1, 6),
                "past the t0 entry the window must be free again — rejections cannot hold slots");
    }

    /** Retry-After is anchored to the oldest live entry: oldest + window − now. */
    @Test
    void retryAfterReflectsTheOldestLiveEntry() throws Exception {
        String key = "linkforge:rl:it-default:sw:it-b6-retry";
        long t0 = System.nanoTime();
        assertTrue(limiter.tryAcquire(key, 1, 10));
        sleepUntil(t0, 2000);
        long rejectedAtMs = elapsedMs(t0);
        RateLimitDecision rejected = limiter.acquire(key, 1, 10);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() >= 5 && rejected.retryAfterSeconds() <= 10,
                "expected ~8s (oldest at t0, window 10s, ~2s elapsed), got " + rejected.retryAfterSeconds());
        // Retry-After counts from the rejection moment: the t0 entry dies at t0+10s.
        // Margin covers the ceil to seconds plus scheduler jitter.
        sleepUntil(t0, rejectedAtMs + rejected.retryAfterSeconds() * 1000L + 300);
        assertTrue(limiter.tryAcquire(key, 1, 10),
                "sleeping the reported Retry-After must buy a slot");
    }

    /** Racing acquires on one key admit exactly max — members never overwrite each other. */
    @Test
    void racingAcquiresAdmitExactlyMax() throws Exception {
        String key = "linkforge:rl:it-default:sw:it-b6-race";
        int threads = 8;
        int max = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return limiter.tryAcquire(key, max, 10);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            long allowed = futures.stream().filter(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
            assertEquals(max, allowed, "member collisions would under-count and admit fewer than max");
        } finally {
            pool.shutdownNow();
        }
    }

    /** A leftover fixed-window STRING key at the old name must not WRONGTYPE the new namespace. */
    @Test
    void legacyStringKeyDoesNotBreakTheSlidingNamespace() throws Exception {
        String legacy = "linkforge:rl:it-default:it-b6-legacy";
        String key = "linkforge:rl:it-default:sw:it-b6-legacy";
        redis.opsForValue().set(legacy, "123");
        long failOpensBefore = failOpenTotal();

        assertTrue(limiter.tryAcquire(key, 2, 5));
        RateLimitDecision second = limiter.acquire(key, 2, 5);
        assertTrue(second.allowed());
        RateLimitDecision third = limiter.acquire(key, 2, 5);
        assertFalse(third.allowed());
        assertTrue(third.retryAfterSeconds() >= 1 && third.retryAfterSeconds() <= 5);

        assertEquals(failOpensBefore, failOpenTotal(), "the sw: path must not fail open");
        assertEquals(org.springframework.data.redis.connection.DataType.ZSET, redis.type(key),
                "the sw: key must be a ZSET");
        Long pttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(pttl != null && pttl > 0 && pttl <= 5000, "PTTL " + pttl + " not within the window");
    }

    private long failOpenTotal() {
        return meters.find("linkforge.ratelimit.failopen").counters().stream()
                .mapToLong(c -> (long) c.count()).sum();
    }

    private static long elapsedMs(long t0) {
        return Duration.ofNanos(System.nanoTime() - t0).toMillis();
    }

    private static void sleepUntil(long t0, long targetMs) throws InterruptedException {
        long remaining = targetMs - elapsedMs(t0);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(TimeUnit.MILLISECONDS.toNanos(remaining));
        }
    }
}

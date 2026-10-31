package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.ShortLinkMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeBloomFilterTest {

    private final ShortLinkMapper mapper = mock(ShortLinkMapper.class);
    private final LinkForgeProperties properties = new LinkForgeProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CodeBloomFilter bloom = new CodeBloomFilter(mapper, properties, meters,
            mock(RedisMessageListenerContainer.class));

    @Test
    void seededCodesArePositiveAndUnseenCodesNegative() {
        when(mapper.selectAllCodes()).thenReturn(List.of("abc1234", "xyz7890"));

        bloom.load();

        assertTrue(bloom.isReady());
        assertTrue(bloom.mightContain("abc1234"));
        assertTrue(bloom.mightContain("xyz7890"));
        assertTrue(IntStream.range(0, 100)
                        .mapToObj(i -> bloom.mightContain("unseen-" + i))
                        .noneMatch(Boolean::booleanValue),
                "never-seeded codes must read negative at fpp=1e-4");
        assertEquals(1.0, meters.get("linkforge.bloom.loaded").counter().count());
    }

    @Test
    void putsDuringTheLoadWindowAreNotLost() {
        // A9 audit S1: the filter is published before seeding, so a create that
        // commits after the snapshot (mid-load) lands in the same instance.
        when(mapper.selectAllCodes()).thenAnswer(inv -> {
            bloom.put("racer");
            return List.of("seeded1");
        });

        bloom.load();

        assertTrue(bloom.isReady());
        assertTrue(bloom.mightContain("seeded1"));
        assertTrue(bloom.mightContain("racer"), "put landing in the seeding window must survive");
    }

    @Test
    void putAfterLoadMakesNewCodesPositive() {
        when(mapper.selectAllCodes()).thenReturn(List.of());
        bloom.load();

        bloom.put("fresh001");

        assertTrue(bloom.mightContain("fresh001"));
    }

    @Test
    void disabledFilterAlwaysAnswersTrueAndNeverLoads() {
        properties.getBloom().setEnabled(false);

        bloom.load();

        verifyNoInteractions(mapper);
        assertFalse(bloom.isReady());
        assertTrue(bloom.mightContain("whatever"), "disabled bloom must fail open to the DB path");
    }

    @Test
    void loadFailureFailsOpenInsteadOfBreakingRedirects() {
        when(mapper.selectAllCodes()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(bloom::load);

        assertFalse(bloom.isReady());
        assertTrue(bloom.mightContain("anything"), "failed load must fail open");
        assertEquals(1.0, meters.get("linkforge.bloom.loadfailed").counter().count());
    }

    @Test
    void notYetLoadedAnswersTrue() {
        assertTrue(bloom.mightContain("anything"), "pre-load must fail open");
    }

    @Test
    void manualRebuildWhileOneIsRunningIs409Busy() throws Exception {
        // D-C2-13: the IT cannot race two triggers deterministically; pin the 409 here.
        CountDownLatch reachedSeed = new CountDownLatch(1);
        CountDownLatch releaseSeed = new CountDownLatch(1);
        when(mapper.selectAllCodes()).thenAnswer(inv -> {
            reachedSeed.countDown();
            releaseSeed.await();
            return List.of("abc1234");
        });

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Long> running = worker.submit(bloom::rebuildManual);
            assertTrue(reachedSeed.await(5, TimeUnit.SECONDS), "rebuild must reach the DB seed");
            BizException busy = assertThrows(BizException.class, bloom::rebuildManual);
            assertEquals(409, busy.getCode(), "the overlapping manual trigger must read busy");
            assertEquals(1.0, meters.get("linkforge.bloom.rebuild").tag("result", "busy").counter().count());
            releaseSeed.countDown();
            assertEquals(1L, running.get(5, TimeUnit.SECONDS));
            assertTrue(bloom.isReady());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void failedRebuildRestoresThePreviousFilterAndStaysReady() {
        // D-C2-10: a failed rebuild must not unseat a loaded filter (pre-DB 404s for
        // existing codes would be the regression).
        when(mapper.selectAllCodes()).thenReturn(List.of("abc1234"))
                .thenThrow(new RuntimeException("db down"));
        bloom.load();
        assertTrue(bloom.isReady());

        BizException failure = assertThrows(BizException.class, bloom::rebuildManual);
        assertEquals(500, failure.getCode());

        assertTrue(bloom.isReady(), "the loaded filter must survive a failed rebuild");
        assertTrue(bloom.mightContain("abc1234"), "the restored filter keeps its codes");
        assertEquals(1.0, meters.get("linkforge.bloom.rebuild").tag("result", "error").counter().count());
    }
}

package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.ShortLinkMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeBloomFilterTest {

    private final ShortLinkMapper mapper = mock(ShortLinkMapper.class);
    private final LinkForgeProperties properties = new LinkForgeProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CodeBloomFilter bloom = new CodeBloomFilter(mapper, properties, meters);

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
}

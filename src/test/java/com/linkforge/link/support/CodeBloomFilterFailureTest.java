package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.ShortLinkMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit N1 (bloom leg): a failed manual rebuild must not put store detail on
 * the wire. Pinned here at the source because GlobalExceptionHandler echoes
 * a BizException's message verbatim (BizExceptionTest pins that), so the
 * message CodeBloomFilter puts on the exception IS the wire message: the
 * fixed "bloom rebuild failed", with the original failure kept as the cause
 * for the handler's one server-side stack log. (HTTP-level failure
 * injection is not available for this path: the mapper also feeds the
 * filter's @PostConstruct warm-up, so a Spring-level failing stub would
 * break context startup for every test in the class.)
 */
class CodeBloomFilterFailureTest {

    @Test
    void manualRebuildFailureUsesSafeWireMessage() {
        ShortLinkMapper mapper = mock(ShortLinkMapper.class);
        RuntimeException storeFailure = new RuntimeException(
                "jdbc:mysql://db.internal:3306 linkforge syntax detail marker");
        when(mapper.selectAllCodes()).thenThrow(storeFailure);
        CodeBloomFilter filter = new CodeBloomFilter(mapper, new LinkForgeProperties(),
                new SimpleMeterRegistry(), mock(RedisMessageListenerContainer.class));

        BizException ex = assertThrows(BizException.class, filter::rebuildManual);

        assertEquals(500, ex.getHttpStatus());
        assertEquals("bloom rebuild failed", ex.getMessage(),
                "fixed wire message — no part of the store failure may leak into it");
        assertSame(storeFailure, ex.getCause(), "the cause stays on the exception for the server log");
    }
}

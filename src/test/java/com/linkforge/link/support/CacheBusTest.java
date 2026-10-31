package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * C2 wave A: the {@code <code>|<origin>} payload contract pinned by BloomBroadcastIT;
 * malformed payloads parse to null (counted and dropped) instead of throwing on the
 * listener thread.
 */
class CacheBusTest {

    @Test
    void wellFormedPayloadSplitsIntoCodeAndOrigin() {
        CacheBus.Put put = CacheBus.parsePut("abc1234|i-0f3ea9c2");
        assertEquals("abc1234", put.code());
        assertEquals("i-0f3ea9c2", put.origin());
    }

    @Test
    void originMayItselfContainPipes() {
        CacheBus.Put put = CacheBus.parsePut("abc1234|we|ird");
        assertEquals("abc1234", put.code());
        assertEquals("we|ird", put.origin());
    }

    @Test
    void malformedPayloadsParseToNullInsteadOfThrowing() {
        assertNull(CacheBus.parsePut("no-separator"), "a bare code without origin is malformed");
        assertNull(CacheBus.parsePut("|i-42"), "an empty code is malformed");
        assertNull(CacheBus.parsePut("abc1234|"), "an empty origin is malformed");
        assertNull(CacheBus.parsePut(""), "an empty payload is malformed");
    }
}

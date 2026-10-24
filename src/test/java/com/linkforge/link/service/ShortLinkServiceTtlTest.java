package com.linkforge.link.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-wave1 D-Bw1-5: positive and negative cache TTLs are base + uniform [0, jitter]
 * (upward only); jitter=0 must degenerate to the bare base.
 */
class ShortLinkServiceTtlTest {

    @Test
    void zeroJitterDegeneratesToBase() {
        for (int i = 0; i < 1000; i++) {
            assertEquals(3600, ShortLinkService.jitteredTtlSeconds(3600, 0));
        }
    }

    @Test
    void negativeJitterDegeneratesToBase() {
        assertEquals(60, ShortLinkService.jitteredTtlSeconds(60, -5));
    }

    @Test
    void jitteredTtlStaysWithinBasePlusJitterAndSpreadOut() {
        long base = 3600;
        long jitter = 300;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            long ttl = ShortLinkService.jitteredTtlSeconds(base, jitter);
            assertTrue(ttl >= base && ttl <= base + jitter, "out of range: " + ttl);
            seen.add(ttl);
        }
        assertTrue(seen.stream().anyMatch(t -> t > base), "at least one strictly-jittered TTL expected");
        assertTrue(seen.contains(base), "the bare base must be reachable too");
    }
}

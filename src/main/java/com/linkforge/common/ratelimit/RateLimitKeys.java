package com.linkforge.common.ratelimit;

/**
 * The sliding window (ZSET) namespace sits under {@code {prefix}sw:} so keys are never
 * shared with the fixed-window-era STRING counters: a ZSET command against a leftover
 * STRING key would raise WRONGTYPE and silently fail the limiter open (D-Bw345-10).
 */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    public static String sw(String keyPrefix, String logicalKey) {
        return keyPrefix + "sw:" + logicalKey;
    }
}

package com.linkforge.common.ratelimit;

/**
 * Outcome of one acquire against the sliding window log. {@code retryAfterSeconds} is
 * meaningful only when {@code allowed} is false.
 */
public record RateLimitDecision(boolean allowed, int retryAfterSeconds, long count) {
}

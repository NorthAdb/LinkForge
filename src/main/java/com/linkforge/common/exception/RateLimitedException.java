package com.linkforge.common.exception;

import lombok.Getter;

/**
 * A 429 whose {@code Retry-After} comes from the rate limiter's sliding window.
 * The handler keeps the response body identical to a plain 429 and adds the header.
 */
@Getter
public class RateLimitedException extends BizException {

    private final int retryAfterSeconds;

    public RateLimitedException(int retryAfterSeconds) {
        super(429, "too many requests, retry later");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}

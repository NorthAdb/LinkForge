package com.linkforge.common.exception;

import com.linkforge.common.result.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Audit fix (exception hygiene): BizException split httpStatus / code, and
 * cause-carrying constructors. Legacy constructors dual-write, so every
 * existing throw site keeps its old wire behavior.
 */
class BizExceptionTest {

    @Test
    void legacyConstructorsDualWriteStatusAndCode() {
        BizException byCode = new BizException(404, "gone");
        assertEquals(404, byCode.getHttpStatus());
        assertEquals(404, byCode.getCode());

        BizException byMessage = new BizException("bad");
        assertEquals(400, byMessage.getHttpStatus());
        assertEquals(400, byMessage.getCode());
    }

    @Test
    void splitConstructorKeepsStatusAndCodeApart() {
        BizException ex = new BizException(400, 1001, "business rule violated");
        assertEquals(400, ex.getHttpStatus());
        assertEquals(1001, ex.getCode());
    }

    @Test
    void causeConstructorsPreserveTheOriginalStack() {
        Throwable cause = new IllegalStateException("root");

        assertSame(cause, new BizException(500, "wrapped", cause).getCause());
        assertSame(cause, new BizException("wrapped", cause).getCause());
        assertSame(cause, new BizException(500, 9001, "wrapped", cause).getCause());
        // the dual-write still applies on the legacy cause path
        assertEquals(500, new BizException(500, "wrapped", cause).getHttpStatus());
        assertEquals(400, new BizException("wrapped", cause).getCode());
    }

    @Test
    void rateLimitedExceptionKeepsIts429Semantics() {
        RateLimitedException ex = new RateLimitedException(30);
        assertEquals(429, ex.getHttpStatus());
        assertEquals(429, ex.getCode());
        assertEquals(30, ex.getRetryAfterSeconds());
    }

    @Test
    void handlerUsesHttpStatusForTheWireAndCodeForTheBody() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> split = handler.handleBiz(new BizException(400, 1001, "x"));
        assertEquals(400, split.getStatusCode().value());
        assertEquals(1001, split.getBody().getCode());

        ResponseEntity<ApiResponse<Void>> legacy = handler.handleBiz(new BizException(404, "x"));
        assertEquals(404, legacy.getStatusCode().value());
        assertEquals(404, legacy.getBody().getCode());

        // safety net kept from the old handler: a dual-written value outside
        // the HTTP range clamps the status to 400 while the body keeps it
        ResponseEntity<ApiResponse<Void>> outOfRange = handler.handleBiz(new BizException(1001, "x"));
        assertEquals(400, outOfRange.getStatusCode().value());
        assertEquals(1001, outOfRange.getBody().getCode());
    }
}

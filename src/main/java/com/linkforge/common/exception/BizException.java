package com.linkforge.common.exception;

import lombok.Getter;

/**
 * Business failure with a split wire contract. {@code httpStatus} is the HTTP
 * status the global handler answers with; {@code code} is the business code in
 * the response body. They used to be one field doing both jobs, which forced
 * every business code to double as an HTTP status (and silently desynced when
 * it could not: the handler clamped the status to 400 while the body kept the
 * raw code).
 *
 * <p>The legacy constructors dual-write both fields from the single value, so
 * every existing throw site keeps its exact old behavior. New sites that need
 * a business code of their own use {@link #BizException(int, int, String)}.
 * The cause-carrying constructors exist so a catch-and-rethrow site (QR
 * rendering, bloom rebuild) stops discarding the original stack trace.
 */
@Getter
public class BizException extends RuntimeException {

    private final int httpStatus;
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(String message, Throwable cause) {
        this(400, 400, message, cause);
    }

    public BizException(int code, String message) {
        this(code, code, message, null);
    }

    public BizException(int code, String message, Throwable cause) {
        this(code, code, message, cause);
    }

    public BizException(int httpStatus, int code, String message) {
        this(httpStatus, code, message, null);
    }

    public BizException(int httpStatus, int code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}

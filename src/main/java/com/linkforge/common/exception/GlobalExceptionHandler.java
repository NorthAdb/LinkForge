package com.linkforge.common.exception;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitedException ex) {
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        // httpStatus drives the wire status, code is body-only. The range guard
        // stays as a safety net for a dual-written legacy value outside 400-599,
        // which used to clamp to 400 here.
        int status = ex.getHttpStatus() >= 400 && ex.getHttpStatus() <= 599 ? ex.getHttpStatus() : 400;
        // S/N wave (audit N1): this handler is the SINGLE server-side stack
        // log for a BizException failure. A server-side failure (5xx) or any
        // exception carrying a cause is logged once, here — the cause object
        // used to die with the request, leaving nothing an operator could
        // find. Throw sites must not log the same stack themselves (the one
        // historical site, bloom rebuild, was adjusted to match). The wire
        // body stays exactly the exception's fixed message: no cause text,
        // no SQL, no connection detail ever crosses to the client; the
        // correlation id is the join key between the two sides.
        if (status >= 500 || ex.getCause() != null) {
            log.error("business failure: code={} cid={}", ex.getCode(),
                    CorrelationIdFilter.currentId(currentRequest()), ex);
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(Exception ex) {
        String msg = "validation failed";
        if (ex instanceof MethodArgumentNotValidException manv && manv.getBindingResult().getFieldError() != null) {
            msg = manv.getBindingResult().getFieldError().getDefaultMessage();
        } else if (ex instanceof BindException be && be.getBindingResult().getFieldError() != null) {
            msg = be.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ApiResponse.fail(400, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(Exception ex) {
        return ApiResponse.fail(400, "malformed request body");
    }

    /**
     * A missing required query/form parameter is client input. The catch-all below
     * would otherwise turn it into HTTP 500 and log a stack, which is what
     * {@code GET /stats/breakdown} without {@code dim} used to do.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return ApiResponse.fail(400, ex.getParameterName() + " is required");
    }

    /**
     * The multipart resolver throws before any controller runs, so the catch-all below
     * would render an over-cap upload as a 500 (D-C3-25 caps import files at 256 KiB).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleOversizedUpload(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail(400, "uploaded file exceeds the size limit");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception ex) {
        log.error("unhandled error", ex);
        return ApiResponse.fail(500, "internal error");
    }
}

package com.linkforge.common.exception;

import com.linkforge.common.result.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
        int status = ex.getCode() >= 400 && ex.getCode() <= 599 ? ex.getCode() : 400;
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
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

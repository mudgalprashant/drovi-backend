package com.pm.drovi_backend.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * The single exit for every unhandled failure.
 *
 * <p>INVARIANT: a 5xx body carries a generic message and the correlation id, and nothing
 * else. Exception text routinely contains SQL fragments, file paths and upstream provider
 * messages — all of which are information disclosure, and none of which help the caller.
 *
 * <h2>Why this class knows about the sandbox surface</h2>
 *
 * A sandbox is pretending to be somebody else's API. If an unexpected exception escaped
 * from {@code /s/…} and were rendered in the console's error shape, the caller's own error
 * handling would receive a payload the real product never sends — the exact confusion the
 * two-boundary contract exists to prevent. So failures under {@code /s/} are rendered in
 * the sandbox's platform shape instead.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String SANDBOX_PREFIX = "/s/";

    @ExceptionHandler(DroviException.class)
    ResponseEntity<Object> handleDrovi(DroviException e, HttpServletRequest request) {
        // Deliberate failures are expected traffic, not incidents: log at INFO and do not
        // attach a stack trace, or real problems drown in them.
        log.info("api.error code={} path={}", e.getErrorCode().code(), request.getRequestURI());
        return render(e.getErrorCode(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        // Field names and constraint messages are safe to return — they describe the
        // caller's own request. Values are not echoed.
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("request validation failed");
        return render(ErrorCode.VALIDATION_FAILED, detail, request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<Object> handleNoHandler(NoHandlerFoundException e, HttpServletRequest request) {
        return render(ErrorCode.NOT_FOUND, "No such endpoint.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception e, HttpServletRequest request) {
        // The only place a stack trace belongs is the log, tied to the correlation id the
        // caller was given.
        log.error("api.error.unexpected path={} correlationId={}",
                request.getRequestURI(), CorrelationIdFilter.current(), e);
        return render(ErrorCode.INTERNAL, "Something went wrong on our side.", request);
    }

    private ResponseEntity<Object> render(ErrorCode code, String message, HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith(SANDBOX_PREFIX)) {
            return ResponseEntity.status(code.status())
                    .body(ApiError.sandboxShaped("SANDBOX_ERROR",
                            "The sandbox could not serve this request."));
        }
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, message, CorrelationIdFilter.current()));
    }
}

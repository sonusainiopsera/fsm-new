package com.fieldservice.common.web;

import com.fieldservice.common.api.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

/**
 * Centralised exception-to-HTTP-response mapping.
 *
 * <p>Handlers are ordered from most-specific to least-specific within this class;
 * Spring picks the most applicable handler for each exception type.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String TRACE_ID_MDC_KEY = "traceId";

    // ── 400 Bad Request ───────────────────────────────────────────────────────

    /**
     * Bean Validation failures ({@code @Valid} on request bodies or path parameters).
     * Returns one {@code fieldError} entry per violated constraint.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getAllErrors()
                .stream()
                .map(error -> {
                    String field = (error instanceof FieldError fe) ? fe.getField() : error.getObjectName();
                    String message = error.getDefaultMessage();
                    return new ErrorResponse.FieldError(field, message);
                })
                .toList();

        log.debug("Validation failed: {}", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.withFieldErrors(
                        "VALIDATION_FAILED",
                        "Request validation failed",
                        fieldErrors,
                        currentTraceId()
                ));
    }

    // ── 403 Forbidden ─────────────────────────────────────────────────────────

    /**
     * Access-control violations.
     *
     * <p>Returns a generic message regardless of the underlying cause to prevent
     * existence disclosure — a caller cannot distinguish "you are not allowed"
     * from "this resource does not exist".
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        // Do not log the caller or userId — avoid leaking information in logs
        log.debug("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        "ACCESS_DENIED",
                        "Access denied",
                        currentTraceId()
                ));
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────

    /**
     * Optimistic-locking collisions raised by Hibernate when a concurrent update
     * modifies the same row between the read and the write in the current request.
     *
     * <p>Callers should retry after a short back-off.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict on {}: {}", ex.getPersistentClassName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        "OPTIMISTIC_LOCK_CONFLICT",
                        "The resource was modified by another request. Please retry.",
                        currentTraceId()
                ));
    }

    // ── 500 Internal Server Error ─────────────────────────────────────────────

    /**
     * Catch-all for unexpected exceptions.
     * Logs the full stack trace server-side but returns only the trace ID to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String traceId = currentTraceId();
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Reference: " + traceId,
                        traceId
                ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String currentTraceId() {
        String mdc = MDC.get(TRACE_ID_MDC_KEY);
        return (mdc != null && !mdc.isBlank()) ? mdc : UUID.randomUUID().toString();
    }
}

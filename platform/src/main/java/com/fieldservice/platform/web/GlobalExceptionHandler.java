package com.fieldservice.platform.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fieldservice.platform.api.*;
import com.fieldservice.platform.pagination.InvalidCursorException;
import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler providing a uniform JSON error envelope for all
 * non-2xx HTTP responses.
 *
 * <h3>Non-disclosure contract</h3>
 * {@link ScopedAccessDeniedException} and {@link ForbiddenException} both map
 * to the same static 403 body — byte-identical whether the record is absent or
 * out-of-scope, preventing existence-disclosure attacks (BR-19, OWASP A01).
 *
 * <h3>Logging policy</h3>
 * <ul>
 *   <li>4xx — {@code WARN} without stack trace (client error, not actionable)</li>
 *   <li>5xx — {@code ERROR} with full stack trace (ops needs it)</li>
 *   <li>ScopedAccessDeniedException — silent here; logged in ScopedQueryExecutor</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Constraint name → safe user-facing message (raw DB text never echoed)
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.of(
            "uq_app_user_email",                    "An account with this email already exists",
            "uq_part_number",                       "A part with this part number already exists",
            "uq_stock_balance_part_location",       "A stock balance entry already exists for this part and location",
            "uq_sla_policy_priority_effective_from","An SLA policy with this priority and effective date already exists",
            "uq_role_name",                         "A role with this name already exists"
    );

    // Static 403 body — identical for absent and out-of-scope resources
    private static final String FORBIDDEN_MESSAGE = "Access denied";

    // ── Typed domain exceptions ───────────────────────────────────────────

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, WebRequest req) {
        log.warn("Not found [traceId={}]: {}", traceId(), ex.getMessage());
        return response(HttpStatus.NOT_FOUND,
                ErrorResponse.of(ErrorCode.NOT_FOUND, ex.getMessage(), traceId()));
    }

    @ExceptionHandler({ForbiddenException.class, ScopedAccessDeniedException.class,
                       AccessDeniedException.class})
    public ResponseEntity<ErrorResponse> handleForbidden(Exception ex, WebRequest req) {
        // Do NOT log ScopedAccessDeniedException — already logged in ScopedQueryExecutor
        if (!(ex instanceof ScopedAccessDeniedException)) {
            log.warn("Forbidden [traceId={}]: {}", traceId(), ex.getMessage());
        }
        return forbidden();
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, WebRequest req) {
        log.warn("Unauthenticated [traceId={}]", traceId());
        return response(HttpStatus.UNAUTHORIZED,
                ErrorResponse.of(ErrorCode.FORBIDDEN, "Authentication required", traceId()));
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(IllegalTransitionException ex, WebRequest req) {
        log.warn("Illegal transition [traceId={}]: {}", traceId(), ex.getMessage());
        return response(HttpStatus.CONFLICT,
                ErrorResponse.of(ErrorCode.ILLEGAL_TRANSITION, ex.getMessage(), traceId()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, WebRequest req) {
        log.warn("Conflict [traceId={}]: {}", traceId(), ex.getMessage());
        return response(HttpStatus.CONFLICT,
                ErrorResponse.of(ErrorCode.CONFLICT, ex.getMessage(), traceId()));
    }

    @ExceptionHandler(BusinessGuardException.class)
    public ResponseEntity<ErrorResponse> handleBusinessGuard(BusinessGuardException ex, WebRequest req) {
        log.warn("Business guard refused [traceId={}]: {}", traceId(), ex.getMessage());
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorResponse.of(ErrorCode.GUARD_REFUSED, ex.getMessage(), traceId()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitedException ex, WebRequest req) {
        log.warn("Rate limited [traceId={}]", traceId());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(ErrorResponse.of(ErrorCode.RATE_LIMITED, ex.getMessage(), traceId()));
    }

    @ExceptionHandler(ProviderDegradedException.class)
    public ResponseEntity<ErrorResponse> handleProviderDegraded(ProviderDegradedException ex, WebRequest req) {
        log.error("Provider degraded [traceId={}]: {}", traceId(), ex.getMessage(), ex);
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorResponse.of(ErrorCode.PROVIDER_DEGRADED, ex.getMessage(), traceId()));
    }

    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAiUnavailable(AiUnavailableException ex, WebRequest req) {
        // Log the internal cause without propagating it to the response
        log.error("AI provider unavailable [traceId={}]: {}", traceId(), ex.getMessage(),
                ex.getCause() != null ? ex.getCause() : ex);
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorResponse.of(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "AI assistance is temporarily unavailable. You can continue without it.",
                        traceId()));
    }

    @ExceptionHandler(AiDailyCapExceededException.class)
    public ResponseEntity<ErrorResponse> handleAiDailyCap(AiDailyCapExceededException ex, WebRequest req) {
        log.info("AI daily cap exceeded [traceId={}]", traceId());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(ErrorResponse.of(ErrorCode.AI_DAILY_LIMIT_REACHED,
                        "Daily AI interaction limit reached.",
                        traceId()));
    }

    // ── Pagination exceptions ─────────────────────────────────────────────

    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSort(InvalidSortException ex, WebRequest req) {
        log.warn("Invalid sort field '{}' [traceId={}]", ex.getField(), traceId());
        var fieldErr = new com.fieldservice.platform.api.FieldError(
                "sort", "Sort field not allowed: " + ex.getField());
        return response(HttpStatus.BAD_REQUEST, ErrorResponse.ofFields(traceId(), List.of(fieldErr)));
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException ex, WebRequest req) {
        log.warn("Invalid pagination cursor [traceId={}]: {}", traceId(), ex.getMessage());
        var fieldErr = new com.fieldservice.platform.api.FieldError(
                "cursor", "Pagination cursor is invalid or expired");
        return response(HttpStatus.BAD_REQUEST, ErrorResponse.ofFields(traceId(), List.of(fieldErr)));
    }

    // ── Spring framework exceptions ───────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest req) {
        List<com.fieldservice.platform.api.FieldError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new com.fieldservice.platform.api.FieldError(
                        fe.getField(),
                        safeMessage(fe.getDefaultMessage())))
                .collect(Collectors.toList());
        log.warn("Validation failed [traceId={}]: {} field error(s)", traceId(), errors.size());
        return response(HttpStatus.BAD_REQUEST, ErrorResponse.ofFields(traceId(), errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, WebRequest req) {
        List<com.fieldservice.platform.api.FieldError> errors = ex.getConstraintViolations()
                .stream()
                .map(cv -> new com.fieldservice.platform.api.FieldError(
                        leafPath(cv.getPropertyPath().toString()),
                        safeMessage(cv.getMessage())))
                .collect(Collectors.toList());
        log.warn("Constraint violation [traceId={}]: {} violation(s)", traceId(), errors.size());
        return response(HttpStatus.BAD_REQUEST, ErrorResponse.ofFields(traceId(), errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex, WebRequest req) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException upe) {
            String field = upe.getPropertyName();
            log.warn("Unknown property '{}' [traceId={}]", field, traceId());
            var fieldErr = new com.fieldservice.platform.api.FieldError(field, "Unknown property: " + field);
            return response(HttpStatus.BAD_REQUEST,
                    ErrorResponse.ofFields(traceId(), List.of(fieldErr)));
        }
        if (cause instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(0).getFieldName();
            log.warn("Invalid format for field '{}' [traceId={}]", field, traceId());
            var fieldErr = new com.fieldservice.platform.api.FieldError(field,
                    "Invalid value for field: " + field);
            return response(HttpStatus.BAD_REQUEST,
                    ErrorResponse.ofFields(traceId(), List.of(fieldErr)));
        }
        log.warn("Unreadable request body [traceId={}]: {}", traceId(), ex.getMessage());
        return response(HttpStatus.BAD_REQUEST,
                ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "Malformed or unparseable request body", traceId()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                               WebRequest req) {
        log.warn("Optimistic lock conflict [traceId={}]: {}", traceId(), ex.getMessage());
        return response(HttpStatus.CONFLICT,
                ErrorResponse.of(ErrorCode.CONFLICT,
                        "Resource was modified concurrently. Please reload and retry.", traceId()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest req) {
        String safeMessage = translateConstraint(ex);
        log.warn("Data integrity violation [traceId={}]: {}", traceId(), safeMessage);
        return response(HttpStatus.CONFLICT,
                ErrorResponse.of(ErrorCode.CONFLICT, safeMessage, traceId()));
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class,
                       HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(Exception ex, WebRequest req) {
        log.warn("Method/media not supported [traceId={}]: {}", traceId(), ex.getMessage());
        HttpStatus status = ex instanceof HttpRequestMethodNotSupportedException
                ? HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        return response(status,
                ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "Request method or content type not supported", traceId()));
    }

    // ── Catch-all ─────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, WebRequest req) {
        log.error("Unhandled exception [traceId={}]", traceId(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", traceId()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> response(HttpStatus status, ErrorResponse body) {
        return ResponseEntity.status(status)
                .header(TraceIdFilter.TRACE_ID_HEADER, body.traceId())
                .body(body);
    }

    private ResponseEntity<ErrorResponse> forbidden() {
        String traceId = traceId();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .body(ErrorResponse.of(ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE, traceId));
    }

    private static String traceId() {
        String id = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        return id != null ? id : "no-trace";
    }

    private static String safeMessage(String msg) {
        if (msg == null) return "Validation failed";
        // Strip any fragment that looks like an internal class name or SQL
        if (msg.contains("com.") || msg.contains("org.") || msg.contains("SQL")
                || msg.contains("constraint") || msg.contains("Exception")) {
            return "Validation failed";
        }
        return msg;
    }

    private static String leafPath(String propertyPath) {
        if (propertyPath == null) return "";
        int dot = propertyPath.lastIndexOf('.');
        return dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
    }

    private static String translateConstraint(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        if (msg != null) {
            for (Map.Entry<String, String> entry : CONSTRAINT_MESSAGES.entrySet()) {
                if (msg.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        // Never echo raw constraint text
        return "The request conflicts with existing data";
    }
}

package com.fieldservice.platform.web;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import io.micrometer.core.instrument.MeterRegistry;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.api.exception.ForbiddenException;
import com.fieldservice.platform.api.exception.IdempotencyConflictException;
import com.fieldservice.platform.api.exception.IllegalTransitionException;
import com.fieldservice.platform.api.exception.InvalidCursorException;
import com.fieldservice.platform.api.exception.InvalidSortException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.api.exception.AiCapExceededException;
import com.fieldservice.platform.api.exception.AiUnavailableException;
import com.fieldservice.platform.api.exception.AuthDependencyUnavailableException;
import com.fieldservice.platform.api.exception.InvalidCredentialsException;
import com.fieldservice.platform.api.exception.PayloadTooLargeException;
import com.fieldservice.platform.api.exception.ProviderDegradedException;
import com.fieldservice.platform.api.exception.RateLimitedException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central exception handler mapping every platform and Spring exception to the
 * uniform {@link ApiErrorResponse} contract.
 *
 * <p>Status mapping:
 * <ul>
 *   <li>400 — validation failures (Bean Validation, unknown JSON property)</li>
 *   <li>401 — unauthenticated (AuthenticationException)</li>
 *   <li>403 — forbidden; body-identical for existence and scope violations</li>
 *   <li>404 — resource not found (non-scoped entities only)</li>
 *   <li>409 — conflict (optimistic lock, illegal transition, duplicate)</li>
 *   <li>422 — business guard refusal</li>
 *   <li>429 — rate limited; adds Retry-After header in seconds</li>
 *   <li>503 — provider degraded</li>
 *   <li>500 — unmapped default; stack trace to logs only</li>
 * </ul>
 *
 * <p>Every response carries an {@code X-Trace-Id} header matching the traceId in the body.
 * No response body ever contains a stack trace, SQL fragment, class name, or constraint name.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String X_TRACE_ID = "X-Trace-Id";

    static final String ACCESS_DENIED_COUNTER = "security.access.denied";

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ---- 400 Bad Request -------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex) {
        String traceId = resolveTraceId();
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.warn("validation_failed trace_id={} field_count={}", traceId, errors.size());
        return errorResponse(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        "Request validation failed.", errors, traceId));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex) {
        String traceId = resolveTraceId();
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(cv -> new FieldError(cv.getPropertyPath().toString(), cv.getMessage()))
                .toList();
        log.warn("constraint_violation trace_id={}", traceId);
        return errorResponse(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        "Request validation failed.", errors, traceId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex) {
        String traceId = resolveTraceId();
        List<FieldError> errors;
        if (ex.getCause() instanceof UnrecognizedPropertyException upe) {
            errors = List.of(new FieldError(upe.getPropertyName(), "unknown property"));
        } else {
            errors = List.of(new FieldError("body", "malformed or unparseable JSON"));
        }
        log.warn("message_not_readable trace_id={} cause={}", traceId,
                ex.getCause() == null ? "null" : ex.getCause().getClass().getSimpleName());
        return errorResponse(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        "Request could not be parsed.", errors, traceId));
    }

    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSort(InvalidSortException ex) {
        String traceId = resolveTraceId();
        log.warn("invalid_sort field={} value={} trace_id={}", ex.getField(), ex.getRejectedValue(), traceId);
        return errorResponse(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        "Invalid sort parameter.",
                        List.of(new FieldError(ex.getField(), ex.getMessage())),
                        traceId));
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCursor(InvalidCursorException ex) {
        String traceId = resolveTraceId();
        log.warn("invalid_cursor reason={} trace_id={}", ex.getMessage(), traceId);
        return errorResponse(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        "Pagination cursor is invalid.",
                        List.of(new FieldError("cursor", ex.getMessage())),
                        traceId));
    }

    // ---- 401 Invalid Credentials (login failures) ----------------------------

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        String traceId = resolveTraceId();
        log.warn("invalid_credentials trace_id={}", traceId);
        return errorResponse(HttpStatus.UNAUTHORIZED,
                ApiErrorResponse.withFieldErrors(ErrorCode.INVALID_CREDENTIALS,
                        "Invalid credentials.", List.of(), traceId));
    }

    // ---- 503 Auth Dependency Unavailable -------------------------------------

    @ExceptionHandler(AuthDependencyUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthDependencyUnavailable(
            AuthDependencyUnavailableException ex) {
        String traceId = resolveTraceId();
        log.error("auth_dependency_unavailable trace_id={}", traceId, ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorResponse.of(ErrorCode.AUTH_DEPENDENCY_UNAVAILABLE,
                        "Authentication service is temporarily unavailable. Please retry later.", traceId));
    }

    // ---- 401 Unauthenticated ---------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        String traceId = resolveTraceId();
        log.warn("authentication_failed trace_id={}", traceId);
        return errorResponse(HttpStatus.UNAUTHORIZED,
                ApiErrorResponse.of(ErrorCode.UNAUTHENTICATED, "Authentication required.", traceId));
    }

    // ---- 403 Forbidden (body-identical for all causes) ------------------------

    @ExceptionHandler({ScopedAccessDeniedException.class, AccessDeniedException.class,
                       ForbiddenException.class})
    public ResponseEntity<ApiErrorResponse> handleForbidden(RuntimeException ex) {
        String traceId = resolveTraceId();
        String resourceType = (ex instanceof ScopedAccessDeniedException sde)
                ? sde.resourceType() : null;
        logDenial(resourceType, traceId);
        if (ex instanceof ScopedAccessDeniedException) {
            meterRegistry.counter(ACCESS_DENIED_COUNTER,
                    "type",     "SCOPE_DENIAL",
                    "resource", resourceType != null ? resourceType : "unknown")
                    .increment();
        }
        return errorResponse(HttpStatus.FORBIDDEN, ApiErrorResponse.forbidden(traceId));
    }

    // ---- 404 Not Found ---------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex) {
        String traceId = resolveTraceId();
        log.warn("not_found resource_type={} trace_id={}", ex.getResourceType(), traceId);
        return errorResponse(HttpStatus.NOT_FOUND,
                ApiErrorResponse.of(ErrorCode.NOT_FOUND, "The requested resource was not found.", traceId));
    }

    // ---- 409 Conflict ----------------------------------------------------------

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        String traceId = resolveTraceId();
        log.warn("idempotency_conflict trace_id={}", traceId);
        return errorResponse(HttpStatus.CONFLICT,
                ApiErrorResponse.of(ErrorCode.IDEMPOTENCY_CONFLICT,
                        ex.getMessage(), traceId));
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalTransition(IllegalTransitionException ex) {
        String traceId = resolveTraceId();
        log.warn("illegal_transition from={} to={} trace_id={}", ex.getFromState(), ex.getToState(), traceId);
        return errorResponse(HttpStatus.CONFLICT,
                ApiErrorResponse.of(ErrorCode.ILLEGAL_TRANSITION,
                        "This state transition is not permitted.", traceId));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex) {
        String traceId = resolveTraceId();
        log.warn("conflict trace_id={}", traceId);
        return errorResponse(HttpStatus.CONFLICT,
                ApiErrorResponse.of(ErrorCode.CONFLICT, "A conflict occurred.", traceId));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        String traceId = resolveTraceId();
        log.warn("optimistic_lock_conflict entity={} trace_id={}",
                ex.getPersistentClass() == null ? "unknown" : ex.getPersistentClass().getSimpleName(),
                traceId);
        return errorResponse(HttpStatus.CONFLICT,
                ApiErrorResponse.of(ErrorCode.CONFLICT,
                        "The resource was modified by another request. Retry with the latest version.", traceId));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex) {
        String traceId = resolveTraceId();
        // Never echo raw constraint text to the client
        log.warn("data_integrity_violation trace_id={}", traceId);
        return errorResponse(HttpStatus.CONFLICT,
                ApiErrorResponse.of(ErrorCode.CONFLICT, "A data integrity constraint was violated.", traceId));
    }

    // ---- 422 SLA Policy Unavailable -------------------------------------------

    @ExceptionHandler(com.fieldservice.sla.SlaPolicyUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleSlaPolicyUnavailable(
            com.fieldservice.sla.SlaPolicyUnavailableException ex) {
        String traceId = resolveTraceId();
        log.warn("sla_policy_unavailable priority={} trace_id={}", ex.getPriority(), traceId);
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorResponse.of(ErrorCode.SLA_POLICY_UNAVAILABLE, ex.getMessage(), traceId));
    }

    // ---- 409 DSAR Illegal Transition -------------------------------------------

    @ExceptionHandler(com.fieldservice.privacy.api.DsarRequestService.DsarIllegalTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleDsarIllegalTransition(
            com.fieldservice.privacy.api.DsarRequestService.DsarIllegalTransitionException ex) {
        String traceId = resolveTraceId();
        log.warn("dsar_illegal_transition trace_id={}", traceId);
        return errorResponse(HttpStatus.CONFLICT,
                ApiErrorResponse.of(ErrorCode.DSAR_ILLEGAL_TRANSITION, ex.getMessage(), traceId));
    }

    // ---- 422 DSAR Guard Refused ------------------------------------------------

    @ExceptionHandler(com.fieldservice.privacy.api.DsarRequestService.DsarGuardRefusalException.class)
    public ResponseEntity<ApiErrorResponse> handleDsarGuardRefusal(
            com.fieldservice.privacy.api.DsarRequestService.DsarGuardRefusalException ex) {
        String traceId = resolveTraceId();
        log.warn("dsar_guard_refused code={} trace_id={}", ex.getCode(), traceId);
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorResponse.of(ErrorCode.DSAR_GUARD_REFUSED, ex.getMessage(), traceId));
    }

    // ---- 422 DSAR Export Not Ready ---------------------------------------------

    @ExceptionHandler(com.fieldservice.privacy.api.DsarRequestService.ExportNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> handleExportNotReady(
            com.fieldservice.privacy.api.DsarRequestService.ExportNotReadyException ex) {
        String traceId = resolveTraceId();
        log.warn("dsar_export_not_ready trace_id={}", traceId);
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorResponse.of(ErrorCode.DSAR_EXPORT_NOT_READY, ex.getMessage(), traceId));
    }

    // ---- 401 DSAR Download Token Invalid ---------------------------------------

    @ExceptionHandler(com.fieldservice.privacy.internal.DownloadTokenService.InvalidDownloadTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDownloadToken(
            com.fieldservice.privacy.internal.DownloadTokenService.InvalidDownloadTokenException ex) {
        String traceId = resolveTraceId();
        log.warn("dsar_download_token_invalid trace_id={}", traceId);
        return errorResponse(HttpStatus.UNAUTHORIZED,
                ApiErrorResponse.of(ErrorCode.DSAR_DOWNLOAD_TOKEN_INVALID, ex.getMessage(), traceId));
    }

    // ---- 422 Retention Floor Violation ----------------------------------------

    @ExceptionHandler(com.fieldservice.privacy.api.RetentionPolicyService.AuditFloorViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleRetentionFloorViolation(
            com.fieldservice.privacy.api.RetentionPolicyService.AuditFloorViolationException ex) {
        String traceId = resolveTraceId();
        log.warn("retention_floor_violation trace_id={}", traceId);
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorResponse.of(ErrorCode.RETENTION_FLOOR_VIOLATION, ex.getMessage(), traceId));
    }

    // ---- 422 Business Guard Refusal -------------------------------------------

    @ExceptionHandler(BusinessGuardException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessGuard(BusinessGuardException ex) {
        String traceId = resolveTraceId();
        log.warn("business_guard_refused trace_id={}", traceId);
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorResponse.of(ErrorCode.GUARD_REFUSED, ex.getMessage(), traceId));
    }

    // ---- 429 Rate Limited ------------------------------------------------------

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimited(RateLimitedException ex) {
        String traceId = resolveTraceId();
        log.warn("rate_limited retry_after={} trace_id={}", ex.getRetryAfterSeconds(), traceId);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(X_TRACE_ID, traceId)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(ErrorCode.RATE_LIMITED,
                        "Rate limit exceeded. Please retry later.", traceId));
    }

    // ---- 503 AI Provider Unavailable ------------------------------------------

    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAiUnavailable(AiUnavailableException ex) {
        String traceId = resolveTraceId();
        log.warn("ai_provider_unavailable operation={} trace_id={}", ex.getOperation(), traceId);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorResponse.of(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "AI assistance is temporarily unavailable. You can continue without it.", traceId));
    }

    // ---- 429 AI Daily Limit Reached ------------------------------------------

    @ExceptionHandler(AiCapExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleAiCapExceeded(AiCapExceededException ex) {
        String traceId = resolveTraceId();
        log.info("ai_daily_cap_exceeded user_id={} trace_id={}", ex.getUserId(), traceId);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(X_TRACE_ID, traceId)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(ErrorCode.AI_DAILY_LIMIT_REACHED,
                        "You have reached your daily AI interaction limit. Please try again tomorrow.", traceId));
    }

    // ---- 503 Provider Degraded ------------------------------------------------

    @ExceptionHandler(ProviderDegradedException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderDegraded(ProviderDegradedException ex) {
        String traceId = resolveTraceId();
        log.error("provider_degraded trace_id={}", traceId, ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorResponse.of(ErrorCode.PROVIDER_DEGRADED,
                        "A required service is currently unavailable. Please retry later.", traceId));
    }

    // ---- 500 Payload Too Large ------------------------------------------------

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex) {
        String traceId = resolveTraceId();
        log.error("outbox_payload_too_large actual_bytes={} max_bytes={} trace_id={}",
                ex.getActualBytes(), ex.getMaxBytes(), traceId);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorResponse.of(ErrorCode.PAYLOAD_TOO_LARGE,
                        "Event payload exceeds the maximum allowed size.", traceId));
    }

    // ---- 500 Fallback ----------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        String traceId = resolveTraceId();
        // Full stack trace to logs only — never echoed to client
        log.error("unhandled_exception trace_id={}", traceId, ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred. Please contact support if this persists.", traceId));
    }

    // ---- Helpers ---------------------------------------------------------------

    private static ResponseEntity<ApiErrorResponse> errorResponse(
            HttpStatus status, ApiErrorResponse body) {
        return ResponseEntity.status(status)
                .header(X_TRACE_ID, body.traceId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    private static void logDenial(String resourceType, String traceId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = (auth != null) ? auth.getName() : "anonymous";
        String roles = (auth != null) ? String.valueOf(auth.getAuthorities()) : "[]";
        log.warn("access_denied actor={} roles={} resource_type={} trace_id={}",
                actor, roles,
                (resourceType != null ? resourceType : "unknown"),
                traceId);
    }
}

package com.fieldservice.api;

import com.fieldservice.aigateway.api.AiCapExceededException;
import com.fieldservice.aigateway.api.AiUnavailableException;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.error.ScopeDenialTranslator;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.ForbiddenException;
import com.fieldservice.platform.exception.IllegalTransitionException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.exception.ProviderDegradedException;
import com.fieldservice.platform.exception.RateLimitedException;
import com.fieldservice.platform.pagination.InvalidCursorException;
import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.workorder.GuardRefusedException;
import com.fieldservice.workorder.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.WorkOrderVersionConflictException;
import com.fieldservice.workorder.holds.InvalidHoldReasonCodeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Global exception handler mapping domain and platform exceptions to uniform HTTP responses.
 *
 * <p>Key non-disclosure rules enforced here:
 * <ul>
 *   <li>{@link ScopedAccessDeniedException} always maps to {@code 403} with the generic
 *       {@code "Access denied."} message — regardless of whether the resource exists or is
 *       merely out of scope. Domain code must not catch this exception and rethrow as 404.</li>
 *   <li>The 403 response body is byte-identical for out-of-scope and nonexistent resources,
 *       preventing cross-role probing via response length or message content.</li>
 *   <li>Resource identifiers are never included in the response body or headers.</li>
 *   <li>Unmapped exceptions always map to 500 with a generic body — never to a success response.</li>
 * </ul>
 *
 * <p>All error responses carry an {@code X-Trace-Id} header for log correlation.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_HEADER = "X-Trace-Id";

    private final ScopeDenialTranslator scopeDenialTranslator;

    public GlobalExceptionHandler(ScopeDenialTranslator scopeDenialTranslator) {
        this.scopeDenialTranslator = scopeDenialTranslator;
    }

    // -------------------------------------------------------------------------
    // Authentication / Authorization
    // -------------------------------------------------------------------------

    @ExceptionHandler(ScopedAccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleScopedAccessDenied(
            ScopedAccessDeniedException ex,
            HttpServletRequest request) {

        return scopeDenialTranslator.translate(ex, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        log.warn("Method security access denied: path={}, traceId={}", request.getRequestURI(), traceId());
        return forbidden();
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorEnvelope> handleForbidden(
            ForbiddenException ex,
            HttpServletRequest request) {

        log.warn("Forbidden: path={}, traceId={}", request.getRequestURI(), traceId());
        return forbidden();
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorEnvelope> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request) {

        log.warn("Authentication failed: path={}, traceId={}", request.getRequestURI(), traceId());
        return errorResponse(HttpStatus.UNAUTHORIZED,
                ErrorEnvelope.Code.UNAUTHENTICATED,
                "Authentication required.");
    }

    // -------------------------------------------------------------------------
    // Not Found
    // -------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(
            NotFoundException ex,
            HttpServletRequest request) {

        log.info("Not found: resourceType={}, traceId={}, path={}",
                ex.getResourceType(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.NOT_FOUND,
                ErrorEnvelope.Code.NOT_FOUND,
                "The requested resource was not found.");
    }

    // -------------------------------------------------------------------------
    // Validation (400)
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        List<FieldError> fieldErrors = buildFieldErrors(ex.getBindingResult());
        log.info("Validation failed: fieldCount={}, traceId={}, path={}",
                fieldErrors.size(), traceId(), request.getRequestURI());
        return validationResponse(fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> new FieldError(
                        leafPath(cv),
                        cv.getMessage()))
                .collect(Collectors.toList());
        log.info("Constraint violation: fieldCount={}, traceId={}, path={}",
                fieldErrors.size(), traceId(), request.getRequestURI());
        return validationResponse(fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> handleUnreadableMessage(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.info("Unreadable request body: traceId={}, path={}", traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.BAD_REQUEST,
                ErrorEnvelope.Code.VALIDATION_FAILED,
                "Request body is missing or cannot be parsed.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorEnvelope> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        log.info("Type mismatch for parameter: param={}, value={}, traceId={}, path={}",
                ex.getName(), ex.getValue(), traceId(), request.getRequestURI());
        List<FieldError> fieldErrors = List.of(new FieldError(
                ex.getName(),
                "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'."));
        return validationResponse(fieldErrors);
    }

    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ErrorEnvelope> handleInvalidSort(
            InvalidSortException ex,
            HttpServletRequest request) {

        log.info("Invalid sort parameter: param={}, traceId={}, path={}",
                ex.getParameterName(), traceId(), request.getRequestURI());
        List<FieldError> fieldErrors = List.of(new FieldError(ex.getParameterName(), ex.getMessage()));
        return validationResponse(fieldErrors);
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorEnvelope> handleInvalidCursor(
            InvalidCursorException ex,
            HttpServletRequest request) {

        log.info("Invalid cursor: traceId={}, path={}", traceId(), request.getRequestURI());
        List<FieldError> fieldErrors = List.of(new FieldError("cursor", "The supplied cursor is invalid or has expired."));
        return validationResponse(fieldErrors);
    }

    // -------------------------------------------------------------------------
    // Business Rules (409 / 422)
    // -------------------------------------------------------------------------

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ErrorEnvelope> handleIllegalTransition(
            IllegalTransitionException ex,
            HttpServletRequest request) {

        log.info("Illegal transition: from={}, to={}, traceId={}, path={}",
                ex.getFromState(), ex.getToState(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.CONFLICT,
                ErrorEnvelope.Code.ILLEGAL_TRANSITION,
                "The requested state transition is not allowed.");
    }

    @ExceptionHandler(IllegalWorkOrderTransitionException.class)
    public ResponseEntity<ErrorEnvelope> handleIllegalWorkOrderTransition(
            IllegalWorkOrderTransitionException ex,
            HttpServletRequest request) {

        log.info("Illegal work order transition: currentState={}, requestedEvent={}, legal={}, traceId={}, path={}",
                ex.getCurrentState(), ex.getRequestedEvent(), ex.getLegalEvents(), traceId(), request.getRequestURI());
        String tid = traceId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(TRACE_HEADER, tid)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.WORK_ORDER_ILLEGAL_TRANSITION,
                        "The requested event '" + ex.getRequestedEvent()
                                + "' is not legal from state '" + ex.getCurrentState()
                                + "'. Legal events: " + ex.getLegalEvents(),
                        tid,
                        Instant.now()));
    }

    @ExceptionHandler(InvalidHoldReasonCodeException.class)
    public ResponseEntity<ErrorEnvelope> handleInvalidHoldReasonCode(
            InvalidHoldReasonCodeException ex,
            HttpServletRequest request) {

        log.info("Invalid hold reason code: code={}, traceId={}, path={}",
                ex.getCode(), traceId(), request.getRequestURI());
        List<FieldError> fieldErrors = List.of(
                new FieldError("holdReasonCode", "Unknown or inactive hold reason code."));
        return validationResponse(fieldErrors);
    }

    @ExceptionHandler(GuardRefusedException.class)
    public ResponseEntity<ErrorEnvelope> handleGuardRefused(
            GuardRefusedException ex,
            HttpServletRequest request) {

        log.info("Work order guard refused: guardId={}, code={}, traceId={}, path={}",
                ex.getGuardId(), ex.getCode(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorEnvelope.Code.WORK_ORDER_GUARD_REFUSED,
                ex.getMessage());
    }

    @ExceptionHandler(WorkOrderVersionConflictException.class)
    public ResponseEntity<ErrorEnvelope> handleWorkOrderVersionConflict(
            WorkOrderVersionConflictException ex,
            HttpServletRequest request) {

        log.info("Work order version conflict: workOrderId={}, expectedVersion={}, traceId={}, path={}",
                ex.getWorkOrderId(), ex.getExpectedVersion(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.CONFLICT,
                ErrorEnvelope.Code.WORK_ORDER_VERSION_CONFLICT,
                "The work order was modified concurrently. Reload and retry with the current version.");
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorEnvelope> handleConflict(
            ConflictException ex,
            HttpServletRequest request) {

        log.info("Conflict: traceId={}, path={}", traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.CONFLICT,
                ErrorEnvelope.Code.CONFLICT,
                "The operation conflicts with the current state of the resource.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorEnvelope> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {

        log.info("Optimistic lock conflict: entity={}, traceId={}, path={}",
                ex.getPersistentClassName(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.CONFLICT,
                ErrorEnvelope.Code.CONFLICT,
                "The resource was modified concurrently. Please retry.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        log.warn("Data integrity violation: traceId={}, path={}", traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.CONFLICT,
                ErrorEnvelope.Code.CONFLICT,
                "The operation violates a data integrity constraint.");
    }

    @ExceptionHandler(BusinessGuardException.class)
    public ResponseEntity<ErrorEnvelope> handleBusinessGuard(
            BusinessGuardException ex,
            HttpServletRequest request) {

        log.info("Business guard refused: guard={}, traceId={}, path={}",
                ex.getGuardName(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorEnvelope.Code.GUARD_REFUSED,
                "The operation was refused by a business rule.");
    }

    // -------------------------------------------------------------------------
    // AI Gateway (503 / 429)
    // -------------------------------------------------------------------------

    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<ErrorEnvelope> handleAiUnavailable(
            AiUnavailableException ex,
            HttpServletRequest request) {

        log.warn("AI provider unavailable: traceId={}, path={}", traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorEnvelope.Code.AI_PROVIDER_UNAVAILABLE,
                "AI assistance is temporarily unavailable. You can continue without it.");
    }

    @ExceptionHandler(AiCapExceededException.class)
    public ResponseEntity<ErrorEnvelope> handleAiCapExceeded(
            AiCapExceededException ex,
            HttpServletRequest request) {

        log.info("AI daily cap exceeded: retryAfter={}s traceId={} path={}",
                ex.getRetryAfterSeconds(), traceId(), request.getRequestURI());
        String tid = traceId();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(TRACE_HEADER, tid)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.AI_DAILY_LIMIT_REACHED,
                        "You have reached your daily AI interaction limit. Please try again tomorrow.",
                        tid,
                        Instant.now()));
    }

    // -------------------------------------------------------------------------
    // Rate Limiting / Provider Degradation
    // -------------------------------------------------------------------------

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorEnvelope> handleRateLimited(
            RateLimitedException ex,
            HttpServletRequest request) {

        log.info("Rate limited: retryAfter={}s, traceId={}, path={}",
                ex.getRetryAfterSeconds(), traceId(), request.getRequestURI());
        String tid = traceId();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(TRACE_HEADER, tid)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.RATE_LIMITED,
                        "Too many requests. Please retry after " + ex.getRetryAfterSeconds() + " seconds.",
                        tid,
                        Instant.now()));
    }

    @ExceptionHandler(ProviderDegradedException.class)
    public ResponseEntity<ErrorEnvelope> handleProviderDegraded(
            ProviderDegradedException ex,
            HttpServletRequest request) {

        log.warn("Provider degraded: provider={}, traceId={}, path={}",
                ex.getProviderName(), traceId(), request.getRequestURI());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorEnvelope.Code.PROVIDER_DEGRADED,
                "A required service is temporarily unavailable. Please retry later.");
    }

    // -------------------------------------------------------------------------
    // Fallback
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unexpected error: path={}, traceId={}", request.getRequestURI(), traceId(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorEnvelope.Code.INTERNAL_ERROR,
                "An unexpected error occurred.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<ErrorEnvelope> forbidden() {
        String tid = traceId();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(TRACE_HEADER, tid)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.FORBIDDEN,
                        "Access denied.",
                        tid,
                        Instant.now()));
    }

    private ResponseEntity<ErrorEnvelope> validationResponse(List<FieldError> fieldErrors) {
        String tid = traceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(TRACE_HEADER, tid)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.VALIDATION_FAILED,
                        "Request validation failed.",
                        fieldErrors,
                        tid,
                        Instant.now()));
    }

    private ResponseEntity<ErrorEnvelope> errorResponse(HttpStatus status, String code, String message) {
        String tid = traceId();
        return ResponseEntity.status(status)
                .header(TRACE_HEADER, tid)
                .body(new ErrorEnvelope(code, message, tid, Instant.now()));
    }

    private static List<FieldError> buildFieldErrors(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
    }

    private static String leafPath(ConstraintViolation<?> cv) {
        String path = cv.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "none";
    }
}

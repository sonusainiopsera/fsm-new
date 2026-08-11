package com.fieldservice.api;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.ForbiddenException;
import com.fieldservice.platform.exception.IllegalTransitionException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.exception.ProviderDegradedException;
import com.fieldservice.platform.exception.RateLimitedException;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
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

    // -------------------------------------------------------------------------
    // Authentication / Authorization
    // -------------------------------------------------------------------------

    @ExceptionHandler(ScopedAccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleScopedAccessDenied(
            ScopedAccessDeniedException ex,
            HttpServletRequest request) {

        if (ex.getResourceType().isEmpty()) {
            log.warn("Scope resolution denied: message={}, traceId={}, path={}",
                    ex.getMessage(), traceId(), request.getRequestURI());
        }
        return forbidden();
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

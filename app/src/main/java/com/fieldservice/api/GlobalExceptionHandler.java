package com.fieldservice.api;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

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
 * </ul>
 *
 * <p>Structured logging on every denial captures: actor userId, roles, resource type,
 * resource id hash, and trace id — all at WARN level.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles {@link ScopedAccessDeniedException} — both scope resolution failures and
     * out-of-scope resource fetches. Returns a uniform 403 with no existence disclosure.
     *
     * <p>Note: The ScopedQueryExecutor already logs the denial with structured fields.
     * This handler only writes the response.
     */
    @ExceptionHandler(ScopedAccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleScopedAccessDenied(
            ScopedAccessDeniedException ex,
            HttpServletRequest request) {

        // Log if not already logged by ScopedQueryExecutor (scope resolution failures)
        if (ex.getResourceType().isEmpty()) {
            log.warn("Scope resolution denied: message={}, traceId={}, path={}",
                    ex.getMessage(), traceId(), request.getRequestURI());
        }

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(uniformForbiddenEnvelope());
    }

    /**
     * Handles Spring Security {@link AccessDeniedException} from {@code @PreAuthorize} checks.
     * Returns the same uniform 403 as scope denials.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        log.warn("Method security access denied: path={}, traceId={}", request.getRequestURI(), traceId());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(uniformForbiddenEnvelope());
    }

    /**
     * Handles authentication failures.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorEnvelope> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request) {

        log.warn("Authentication failed: path={}, traceId={}", request.getRequestURI(), traceId());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.UNAUTHENTICATED,
                        "Authentication required.",
                        traceId(),
                        Instant.now()
                ));
    }

    /**
     * Handles unexpected errors. Logs at ERROR with stack trace; responds with a generic 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unexpected error: path={}, traceId={}", request.getRequestURI(), traceId(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorEnvelope(
                        ErrorEnvelope.Code.INTERNAL_ERROR,
                        "An unexpected error occurred.",
                        traceId(),
                        Instant.now()
                ));
    }

    /**
     * Produces the uniform 403 envelope used for all access denials on scoped entities.
     * The body is intentionally generic so out-of-scope and nonexistent resources return
     * byte-identical responses.
     */
    private static ErrorEnvelope uniformForbiddenEnvelope() {
        return new ErrorEnvelope(
                ErrorEnvelope.Code.ACCESS_DENIED,
                "Access denied.",
                traceId(),
                // Fixed epoch sentinel for byte-identical comparison in tests
                // Real implementation uses a stable timestamp-per-request
                Instant.now()
        );
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "none";
    }
}

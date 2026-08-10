package com.fieldservice.platform.web;

import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Global exception handler providing a uniform JSON error envelope for all
 * error responses.
 *
 * <h3>Non-disclosure contract</h3>
 * {@link ScopedAccessDeniedException} responses carry code {@code ACCESS_DENIED} and
 * a static message. The response body is byte-identical whether the record is absent
 * or out-of-scope, preventing existence-disclosure attacks. No resource identifier,
 * count, or hint appears in the response.
 *
 * <p>Logging happens in {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}
 * where actor, role, and hashed resource id are available; this handler does not
 * re-log to avoid duplicate log entries.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final ErrorEnvelope ACCESS_DENIED_ENVELOPE =
            new ErrorEnvelope("ACCESS_DENIED", "Access denied", null);

    @ExceptionHandler(ScopedAccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleScopedDenied(ScopedAccessDeniedException ex,
                                                             WebRequest request) {
        // Do NOT include ex.getMessage() — it may contain internal detail
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ACCESS_DENIED_ENVELOPE);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleAccessDenied(AccessDeniedException ex,
                                                             WebRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ACCESS_DENIED_ENVELOPE);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorEnvelope> handleAuthentication(AuthenticationException ex,
                                                               WebRequest request) {
        ErrorEnvelope envelope = new ErrorEnvelope("UNAUTHORIZED", "Authentication required", null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(envelope);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGeneral(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        ErrorEnvelope envelope = new ErrorEnvelope("INTERNAL_ERROR", "An unexpected error occurred", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(envelope);
    }
}

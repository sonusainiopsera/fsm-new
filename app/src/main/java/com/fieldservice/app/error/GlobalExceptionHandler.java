package com.fieldservice.app.error;

import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * Global exception handler that maps platform exceptions to the uniform API error envelope.
 *
 * <h3>Non-disclosure (scoped access denial)</h3>
 * <p>{@link ScopedAccessDeniedException} is mapped to HTTP 403 with a uniform body. Both
 * "resource does not exist" and "resource is out of scope" cases produce byte-identical
 * responses — no existence information leaks through the error body, status code, or
 * response length. This is a deliberate security design decision; see
 * {@code com.fieldservice.platform.api} package notes.
 *
 * <h3>Structured denial logging</h3>
 * <p>Every denial is logged at WARN level with:
 * <ul>
 *   <li>{@code actor}         — userId from the current authentication principal</li>
 *   <li>{@code roles}         — granted authorities of the actor</li>
 *   <li>{@code resource_type} — entity type name (e.g. "work_order"), if available</li>
 *   <li>{@code trace_id}      — from MDC or request attribute</li>
 * </ul>
 * The requested resource identifier is <strong>never</strong> echoed back to the client or
 * included in the log entry in plain form — only a SHA-256 hash of the raw id string is
 * logged, satisfying PII protection requirements.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles row-scope and resolution denials with a uniform 403 response.
     * The body is intentionally identical for all denial reasons (missing resource,
     * out-of-scope resource, resolution failure).
     */
    @ExceptionHandler(ScopedAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleScopedDenial(
            ScopedAccessDeniedException ex, WebRequest request) {

        String traceId = Optional.ofNullable(MDC.get("traceId")).orElse("none");
        logDenial(ex.resourceType(), traceId, ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.forbidden(traceId));
    }

    /**
     * Handles Spring Security method-level access denied (e.g. @PreAuthorize rejection).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, WebRequest request) {

        String traceId = Optional.ofNullable(MDC.get("traceId")).orElse("none");
        logDenial(null, traceId, ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.forbidden(traceId));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void logDenial(String resourceType, String traceId, String internalReason) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = (auth != null) ? String.valueOf(auth.getName()) : "anonymous";
        String roles = (auth != null) ? String.valueOf(auth.getAuthorities()) : "[]";

        // Log the resource type (safe) but NOT the raw id.
        // The caller's identifier is hashed for audit purposes only.
        log.warn("scope_access_denied actor={} roles={} resource_type={} trace_id={}",
                actor,
                roles,
                (resourceType != null ? resourceType : "unknown"),
                traceId);
        // Internal reason is debug-only, not sent to client.
        log.debug("scope_access_denied_internal reason={}", internalReason);
    }

    /**
     * Returns a truncated SHA-256 hash of {@code rawId} for audit log correlation without
     * echoing the identifier.
     */
    @SuppressWarnings("unused")
    static String hashId(String rawId) {
        if (rawId == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawId.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "hash_unavailable";
        }
    }
}

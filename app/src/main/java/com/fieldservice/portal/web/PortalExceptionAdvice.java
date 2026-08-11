package com.fieldservice.portal.web;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.portal.access.ScopeUnavailableException;
import com.fieldservice.portal.service.PortalHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Portal-specific exception-to-HTTP-status mapping.
 *
 * <h3>Disclosure rule (AC-6)</h3>
 * HTTP 404 is returned for <em>both</em> of the following cases, with an identical response body:
 * <ol>
 *   <li>Row-scope miss — the authenticated user requested a resource that exists but belongs
 *       to a different account ({@link ScopeUnavailableException}).</li>
 *   <li>Resource not found — the identifier does not match any row in the database
 *       ({@link com.fieldservice.platform.exception.NotFoundException}).</li>
 * </ol>
 * A caller cannot distinguish the two cases: same status code, same error code, same message.
 *
 * <p>HTTP 403 is returned only when a correctly-authenticated principal with an incorrect
 * <em>role</em> attempts a portal operation ({@link AccessDeniedException}).
 *
 * <p>This advice is scoped to {@code com.fieldservice.portal} controllers; the global
 * {@code GlobalExceptionHandler} handles all other packages.
 */
@RestControllerAdvice(basePackages = "com.fieldservice.portal")
public class PortalExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(PortalExceptionAdvice.class);

    /** Stable code returned for every portal 404 regardless of root cause (non-disclosure). */
    public static final String PORTAL_RESOURCE_NOT_FOUND = "PORTAL_RESOURCE_NOT_FOUND";

    /** Non-disclosure message — never contains resource identifiers or account info. */
    private static final String NOT_FOUND_MESSAGE =
            "The requested resource was not found.";

    private static final String ACCESS_DENIED_MESSAGE =
            "You do not have permission to perform this action.";

    /**
     * Scope miss and genuine not-found both return 404 with an identical body.
     * This is the single authoritative place where the disclosure rule is encoded.
     */
    @ExceptionHandler({
            ScopeUnavailableException.class,
            com.fieldservice.platform.exception.NotFoundException.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorEnvelope handleNotFound(Exception ex) {
        log.debug("portal.not_found: {}", ex.getMessage());
        return new ErrorEnvelope(
                PORTAL_RESOURCE_NOT_FOUND,
                NOT_FOUND_MESSAGE,
                List.of(),
                traceId(),
                Instant.now()
        );
    }

    /**
     * Role violation (wrong role, not wrong account) returns 403.
     * No existence information is disclosed in the body.
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorEnvelope handleAccessDenied(AccessDeniedException ex) {
        log.warn("portal.access_denied: {}", ex.getMessage());
        return new ErrorEnvelope(
                ErrorEnvelope.Code.FORBIDDEN,
                ACCESS_DENIED_MESSAGE,
                List.of(),
                traceId(),
                Instant.now()
        );
    }

    /**
     * Invalid date range parameters (inverted or too wide) return 400 with an actionable message.
     */
    @ExceptionHandler(PortalHistoryService.DateRangeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorEnvelope handleDateRangeException(PortalHistoryService.DateRangeException ex) {
        log.debug("portal.date_range_error: {}", ex.getMessage());
        return new ErrorEnvelope(
                ErrorEnvelope.Code.VALIDATION_FAILED,
                ex.getMessage(),
                List.of(),
                traceId(),
                Instant.now()
        );
    }

    private static String traceId() {
        String tid = MDC.get("traceId");
        return tid != null ? tid : "none";
    }
}

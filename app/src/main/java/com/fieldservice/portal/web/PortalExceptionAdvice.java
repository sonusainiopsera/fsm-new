package com.fieldservice.portal.web;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.portal.access.ScopeUnavailableException;
import com.fieldservice.portal.service.PortalInvitationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Exception handler encoding the single portal disclosure rule (AC-6):
 *
 * <ul>
 *   <li>HTTP 404 with code {@code PORTAL_RESOURCE_NOT_FOUND} for row-scope misses
 *       ({@link ScopeUnavailableException}) and unknown resource ids — the response body
 *       and status code are byte-identical whether the resource exists but is out-of-scope
 *       or genuinely does not exist.</li>
 *   <li>HTTP 403 with code {@code FORBIDDEN} for authenticated-but-wrong-role access
 *       ({@link AccessDeniedException} from {@code @PreAuthorize}).</li>
 *   <li>HTTP 404 with the same body for invalid/expired/consumed invitation tokens
 *       ({@link PortalInvitationService.InvitationNotFoundException}).</li>
 *   <li>HTTP 409 with {@code CONFLICT} for duplicate linkage
 *       ({@link PortalInvitationService.DuplicateLinkageException}).</li>
 * </ul>
 *
 * <p>Scoped to {@code com.fieldservice.portal.web} so only portal controllers use these
 * mappings. The global {@link com.fieldservice.platform.web.GlobalExceptionHandler} handles
 * all other packages.
 */
@RestControllerAdvice(basePackages = "com.fieldservice.portal.web")
public class PortalExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(PortalExceptionAdvice.class);
    private static final String X_TRACE_ID = "X-Trace-Id";

    /**
     * Row-scope miss: no active portal linkage for the authenticated user.
     * Returns 404 — identical body to a genuinely non-existent resource.
     */
    @ExceptionHandler(ScopeUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleScopeUnavailable(ScopeUnavailableException ex) {
        String traceId = resolveTraceId();
        log.warn("portal_scope_unavailable trace_id={}", traceId);
        return notFound(traceId);
    }

    /**
     * Unknown, expired, or consumed invitation token.
     * Returns 404 — identical body to scope miss so token existence is not disclosed.
     */
    @ExceptionHandler(PortalInvitationService.InvitationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleInvitationNotFound(
            PortalInvitationService.InvitationNotFoundException ex) {
        String traceId = resolveTraceId();
        log.warn("portal_invitation_not_found trace_id={}", traceId);
        return notFound(traceId);
    }

    /**
     * Duplicate linkage — user already has an active portal account.
     * Returns 409 CONFLICT.
     */
    @ExceptionHandler(PortalInvitationService.DuplicateLinkageException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateLinkage(
            PortalInvitationService.DuplicateLinkageException ex) {
        String traceId = resolveTraceId();
        log.warn("portal_duplicate_linkage trace_id={}", traceId);
        ApiErrorResponse body = ApiErrorResponse.of(ErrorCode.CONFLICT,
                "User already has a portal account linkage.", traceId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_TRACE_ID, traceId)
                .body(body);
    }

    /**
     * Role violation: authenticated but wrong role for this portal operation.
     * Returns 403 with the platform's standard non-disclosing body.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        String traceId = resolveTraceId();
        log.warn("portal_access_denied trace_id={}", traceId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_TRACE_ID, traceId)
                .body(ApiErrorResponse.forbidden(traceId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ResponseEntity<ApiErrorResponse> notFound(String traceId) {
        ApiErrorResponse body = ApiErrorResponse.of(
                ErrorCode.PORTAL_RESOURCE_NOT_FOUND,
                "The requested portal resource does not exist.",
                traceId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_TRACE_ID, traceId)
                .body(body);
    }

    private static String resolveTraceId() {
        String mdc = MDC.get("traceId");
        return (mdc != null && !mdc.isBlank()) ? mdc : UUID.randomUUID().toString();
    }
}

package com.fieldservice.portal.access;

/**
 * Thrown by {@link CustomerAccessScope} when no active portal linkage row exists for
 * the authenticated principal.
 *
 * <p>Mapped to HTTP 404 with code {@code PORTAL_RESOURCE_NOT_FOUND} by
 * {@link com.fieldservice.portal.web.PortalExceptionAdvice}. The body is identical
 * to the response for a genuinely non-existent resource identifier, so a caller
 * cannot distinguish "not linked" from "does not exist" (AC-6 non-disclosure rule).
 *
 * <p>This exception intentionally carries no identity or account information — the
 * message is a fixed diagnostic string suitable for server-side logging only.
 */
public class ScopeUnavailableException extends RuntimeException {

    public ScopeUnavailableException(String message) {
        super(message);
    }
}

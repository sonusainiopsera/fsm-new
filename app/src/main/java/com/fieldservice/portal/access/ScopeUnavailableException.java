package com.fieldservice.portal.access;

/**
 * Thrown by {@link CustomerAccessScope} when the authenticated principal has no
 * {@link com.fieldservice.portal.domain.PortalAccountUser} linkage row, or when the
 * linkage is in a non-ACTIVE state.
 *
 * <p>This exception is mapped to HTTP 404 with code {@code PORTAL_RESOURCE_NOT_FOUND} by
 * {@link com.fieldservice.portal.web.PortalExceptionAdvice} so that the existence of the
 * linkage row itself is not disclosed.
 *
 * <p>The system never fails open: if the account id cannot be resolved the predicate
 * builder throws this exception rather than returning an unrestricted predicate.
 */
public class ScopeUnavailableException extends RuntimeException {

    public ScopeUnavailableException(String message) {
        super(message);
    }
}

package com.fieldservice.platform.api;

/**
 * Thrown when a requested resource does not exist.
 * Maps to HTTP 404.
 *
 * <p>Do NOT use this for scoped-access failures — use
 * {@link com.fieldservice.platform.security.ScopedAccessDeniedException} instead
 * to preserve the non-disclosure contract.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String entityType, Object id) {
        super(ErrorCode.NOT_FOUND, entityType + " not found: " + id);
    }

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}

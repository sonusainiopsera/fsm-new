package com.fieldservice.platform.exception;

/**
 * Thrown when a non-scoped resource is not found.
 *
 * <p>For <em>scoped</em> entities, do not throw this — throw
 * {@link com.fieldservice.platform.security.ScopedAccessDeniedException} instead
 * so that existence cannot be inferred by an unauthorised caller.
 *
 * <p>Maps to HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    private final String resourceType;
    private final Object resourceId;

    public NotFoundException(String resourceType, Object resourceId) {
        super(resourceType + " not found");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public NotFoundException(String message) {
        super(message);
        this.resourceType = "Resource";
        this.resourceId = null;
    }

    public String getResourceType() { return resourceType; }
    public Object getResourceId() { return resourceId; }
}

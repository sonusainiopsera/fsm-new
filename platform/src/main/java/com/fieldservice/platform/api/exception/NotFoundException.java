package com.fieldservice.platform.api.exception;

/** Thrown when a resource cannot be found and non-disclosure is NOT required. Maps to HTTP 404. */
public class NotFoundException extends RuntimeException {
    private final String resourceType;

    public NotFoundException(String resourceType, String id) {
        super(resourceType + " not found: " + id);
        this.resourceType = resourceType;
    }

    public String getResourceType() { return resourceType; }
}

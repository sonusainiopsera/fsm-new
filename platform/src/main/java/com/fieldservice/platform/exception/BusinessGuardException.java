package com.fieldservice.platform.exception;

/**
 * Thrown when a business rule guard refuses an operation.
 *
 * <p>Examples: attempting to complete a work order before all labour time is recorded,
 * or assigning a technician who lacks required certifications.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity.
 */
public class BusinessGuardException extends RuntimeException {

    private final String guardName;

    public BusinessGuardException(String guardName, String reason) {
        super(reason);
        this.guardName = guardName;
    }

    public BusinessGuardException(String reason) {
        super(reason);
        this.guardName = null;
    }

    public String getGuardName() { return guardName; }
}

package com.fieldservice.workforce.api;

import com.fieldservice.platform.exception.BusinessGuardException;

import java.util.Set;
import java.util.UUID;

/**
 * Thrown when a required regulated certification is not current at assignment time.
 *
 * <p>Maps to HTTP 422 via the platform {@code GlobalExceptionHandler}. Carries the
 * missing type codes so the caller can surface actionable detail.
 *
 * <p>No override parameter exists; the guard cannot be bypassed.
 */
public class CertificationNotCurrentException extends BusinessGuardException {

    private final UUID technicianId;
    private final Set<String> missingTypeCodes;

    public CertificationNotCurrentException(UUID technicianId, Set<String> missingTypeCodes) {
        super("certification-currency",
                "Technician " + technicianId + " lacks current regulated certification(s): " +
                missingTypeCodes);
        this.technicianId    = technicianId;
        this.missingTypeCodes = Set.copyOf(missingTypeCodes);
    }

    public UUID getTechnicianId()        { return technicianId; }
    public Set<String> getMissingTypeCodes() { return missingTypeCodes; }
}

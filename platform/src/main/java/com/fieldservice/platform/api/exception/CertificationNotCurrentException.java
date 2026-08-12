package com.fieldservice.platform.api.exception;

import java.util.List;

/**
 * Thrown by the certification guard when a required REGULATED certification
 * is not current for the technician being assigned.
 *
 * <p>Maps to HTTP 422 with error code {@code CERTIFICATION_NOT_CURRENT}.
 * {@code missingTypeCodes} identifies which regulated certifications are absent
 * or expired so the dispatcher can take corrective action.
 *
 * <p>No override parameter exists — regulated refusals cannot be bypassed.
 */
public class CertificationNotCurrentException extends RuntimeException {

    private final List<String> missingTypeCodes;

    public CertificationNotCurrentException(List<String> missingTypeCodes) {
        super("Technician is missing required regulated certification(s): " + missingTypeCodes);
        this.missingTypeCodes = List.copyOf(missingTypeCodes);
    }

    /** The regulated certification type codes that are not current. */
    public List<String> getMissingTypeCodes() { return missingTypeCodes; }
}

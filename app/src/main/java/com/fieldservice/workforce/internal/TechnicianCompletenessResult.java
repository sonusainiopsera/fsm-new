package com.fieldservice.workforce.internal;

import java.util.List;
import java.util.UUID;

/**
 * Per-technician completeness evaluation result.
 *
 * <p>{@code complete} is {@code true} iff {@code missingFields},
 * {@code missingCertificationTypes}, and {@code expiredCertificationTypes} are all empty.
 * A technician with certifications in {@code expiringSoonCertificationTypes} is still
 * complete today but will become incomplete if the certification is not renewed.
 */
record TechnicianCompletenessResult(
        UUID         technicianId,
        String       employeeCode,
        String       displayName,
        boolean      complete,
        List<String> missingFields,
        List<String> missingCertificationTypes,
        List<String> expiredCertificationTypes,
        List<String> expiringSoonCertificationTypes
) {

    boolean isBlocking() {
        return !complete;
    }
}

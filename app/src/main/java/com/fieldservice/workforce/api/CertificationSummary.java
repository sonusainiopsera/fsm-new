package com.fieldservice.workforce.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection of a technician certification.
 *
 * <p>{@code current} and {@code daysUntilExpiry} are derived at query time —
 * they are NEVER stored.
 */
public record CertificationSummary(
        UUID      id,
        String    typeCode,
        String    typeDisplayName,
        boolean   regulated,
        String    certificateReference,
        LocalDate issuedOn,
        LocalDate expiresOn,
        boolean   current,
        Long      daysUntilExpiry
) {}

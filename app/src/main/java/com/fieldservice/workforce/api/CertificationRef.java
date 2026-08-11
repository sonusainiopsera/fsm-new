package com.fieldservice.workforce.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Public read projection of a single technician certification record.
 *
 * <p>{@code current} and {@code daysUntilExpiry} are derived from {@code expiresOn}
 * at mapping time — they are never stored.
 */
public record CertificationRef(
        UUID id,
        String typeCode,
        String typeDisplayName,
        boolean regulated,
        String certificateReference,
        LocalDate issuedOn,
        LocalDate expiresOn,
        boolean current,
        Long daysUntilExpiry
) {}

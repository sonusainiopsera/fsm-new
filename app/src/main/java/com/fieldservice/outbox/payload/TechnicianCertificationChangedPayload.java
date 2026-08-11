package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox event payload for certification write operations.
 *
 * <p>Consumed by WO-024 pre-expiry sweep. Contains no PII — only IDs and certification dates.
 */
public record TechnicianCertificationChangedPayload(
        UUID certificationId,
        UUID technicianId,
        String certificationTypeCode,
        boolean regulated,
        LocalDate issuedOn,
        LocalDate expiresOn,
        boolean active,
        String operation,
        Instant occurredAt
) {
    public static final String EVENT_TYPE     = "TechnicianCertificationChanged";
    public static final String AGGREGATE_TYPE = "TechnicianCertification";
}

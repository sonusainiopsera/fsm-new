package com.fieldservice.outbox.payload;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox event payload for a certification in the WARNING or URGENT cohort.
 *
 * <p>Contains only identifiers and dates — no PII. Recipient resolution is performed
 * by the consumer at delivery time under its own authorisation check.
 */
public record CertificationExpiringAlertPayload(
        UUID certificationId,
        UUID technicianId,
        String certificationTypeCode,
        String alertStage,
        LocalDate expiresOn,
        long daysToExpiry
) {
    public static final String EVENT_TYPE     = "CertificationExpiringAlert";
    public static final String AGGREGATE_TYPE = "TechnicianCertification";
}

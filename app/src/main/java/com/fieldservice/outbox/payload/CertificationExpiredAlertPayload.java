package com.fieldservice.outbox.payload;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox event payload for a certification in the EXPIRED cohort.
 *
 * <p>Contains only identifiers and dates — no PII. Recipient resolution is performed
 * by the consumer at delivery time under its own authorisation check.
 */
public record CertificationExpiredAlertPayload(
        UUID certificationId,
        UUID technicianId,
        String certificationTypeCode,
        LocalDate expiresOn,
        long daysExpired
) {
    public static final String EVENT_TYPE     = "CertificationExpiredAlert";
    public static final String AGGREGATE_TYPE = "TechnicianCertification";
}

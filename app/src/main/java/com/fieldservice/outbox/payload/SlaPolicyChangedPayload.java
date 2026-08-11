package com.fieldservice.outbox.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Purpose-built payload record for the {@code SlaPolicyChanged} event type.
 *
 * <p>Contains only the fields that downstream consumers need. No PII.
 */
public record SlaPolicyChangedPayload(
        UUID policyId,
        String priority,
        int responseMinutes,
        int resolutionMinutes,
        BigDecimal atRiskFraction,
        boolean ratified,
        String operation,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "SlaPolicyChanged";
    public static final String AGGREGATE_TYPE = "SlaPolicy";
}

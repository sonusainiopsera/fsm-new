package com.fieldservice.outbox.payload;

import java.util.UUID;

/** Outbox event payload for technician availability window/absence changes. No PII. */
public record TechnicianAvailabilityChangedPayload(
        UUID technicianId,
        String changeType,
        String entityType
) {
    public static final String EVENT_TYPE     = "workforce.TechnicianAvailabilityChanged";
    public static final String AGGREGATE_TYPE = "TechnicianAvailability";
}

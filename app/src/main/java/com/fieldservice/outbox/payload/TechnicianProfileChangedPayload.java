package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * Outbox event payload for technician profile create/update/deactivate events.
 *
 * <p>Contains identifiers and state only — no PII or encrypted fields.
 * Consumers needing contact details must resolve them under their own authorization.
 */
public record TechnicianProfileChangedPayload(
        UUID technicianId,
        UUID userId,
        String employeeCode,
        String changeType,
        boolean active
) {
    public static final String EVENT_TYPE      = "workforce.TechnicianProfileChanged";
    public static final String AGGREGATE_TYPE  = "Technician";
}

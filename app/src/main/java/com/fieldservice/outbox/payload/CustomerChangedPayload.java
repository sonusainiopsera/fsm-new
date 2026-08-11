package com.fieldservice.outbox.payload;

import java.util.UUID;

/**
 * Outbox event payload for customer create/update/deactivate events.
 *
 * <p>Contains identifiers and state transitions only — no PII. Consumers that
 * need contact details must resolve them from the database under their own authorization.
 */
public record CustomerChangedPayload(
        UUID customerId,
        String accountCode,
        String changeType,
        boolean active
) {
    public static final String EVENT_TYPE = "catalog.CustomerChanged";
    public static final String AGGREGATE_TYPE = "Customer";
}

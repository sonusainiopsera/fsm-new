package com.fieldservice.platform.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single entry in the transactional outbox table.
 * The outbox poller (worker profile) publishes these events to the broker.
 *
 * <p>Event payloads carry identifiers and state transitions ONLY — never PII.
 * Consumers that need customer data resolve it from the database under their
 * own authorization check.</p>
 */
public record OutboxEvent(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OutboxStatus status,
        Instant createdAt,
        Instant processedAt
) {

    public enum OutboxStatus {
        PENDING,
        PROCESSING,
        PUBLISHED,
        FAILED
    }
}

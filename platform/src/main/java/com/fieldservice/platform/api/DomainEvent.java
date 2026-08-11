package com.fieldservice.platform.api;

import com.fieldservice.platform.util.UuidV7;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable domain event ready for atomic outbox persistence.
 *
 * <p><b>Delivery semantics:</b> at-least-once. Consumers MUST treat {@code eventId} as
 * the deduplication key — processing the same {@code eventId} twice must produce the same
 * observable result as processing it once (idempotent consumer pattern).
 *
 * <p><b>Payload contract:</b> payloads are built from purpose-built, per-event-type payload
 * records — never from serialised domain entities. Callers MUST use
 * {@link com.fieldservice.platform.outbox.PiiRedactionUtility#toPayloadMap(Object)} to
 * construct the payload map; this utility rejects fields annotated
 * {@link com.fieldservice.platform.outbox.annotation.Restricted} and masks fields annotated
 * {@link com.fieldservice.platform.outbox.annotation.Confidential}.
 *
 * @param eventId       UUIDv7 unique event identifier; consumer deduplication key
 * @param eventType     stable event type name (e.g. {@code "WorkOrderStateChanged"})
 * @param aggregateType stable aggregate type name (e.g. {@code "WorkOrder"})
 * @param aggregateId   UUID of the aggregate root that produced this event
 * @param occurredAt    instant at which the domain change occurred
 * @param traceId       request trace-id for correlation with logs and Envers revisions
 * @param actorUserId   authenticated actor; {@code null} for system-initiated events
 * @param payload       sanitised payload map produced by {@link com.fieldservice.platform.outbox.PiiRedactionUtility}
 */
public record DomainEvent(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        String traceId,
        UUID actorUserId,
        Map<String, Object> payload
) {

    /**
     * Convenience factory that generates a UUIDv7 {@code eventId} automatically.
     *
     * @param eventType     stable event type identifier
     * @param aggregateType stable aggregate type identifier
     * @param aggregateId   UUID of the aggregate root
     * @param occurredAt    when the domain change occurred
     * @param traceId       request trace-id (may be null for system events)
     * @param actorUserId   authenticated actor (may be null for system events)
     * @param payload       sanitised payload map from {@link com.fieldservice.platform.outbox.PiiRedactionUtility}
     * @return a new {@link DomainEvent} with a fresh UUIDv7 event id
     */
    public static DomainEvent of(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Instant occurredAt,
            String traceId,
            UUID actorUserId,
            Map<String, Object> payload) {
        return new DomainEvent(
                UuidV7.generate(),
                eventType,
                aggregateType,
                aggregateId,
                occurredAt,
                traceId,
                actorUserId,
                payload
        );
    }
}

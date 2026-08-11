package com.fieldservice.platform.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable event record appended to the transactional outbox by {@link DomainEventPublisher}.
 *
 * <p>Delivery semantics: <strong>at-least-once</strong>. Consumers must treat
 * {@code eventId} as the deduplication key and apply idempotent processing.
 *
 * <p>Payload contract: {@code payload} must be a purpose-built payload record
 * (never a raw JPA entity). It must not contain fields annotated
 * {@code @Restricted} with non-null values; {@code @Confidential} fields are
 * masked during serialisation.
 *
 * @param eventId       UUIDv7 primary key; consumer deduplication key
 * @param eventType     stable string constant identifying the event schema
 * @param aggregateType entity type name (e.g. "WORK_ORDER")
 * @param aggregateId   UUID of the aggregate root that changed
 * @param occurredAt    instant the domain change occurred
 * @param traceId       MDC traceId from the originating request (may be null for background jobs)
 * @param actorUserId   authenticated user UUID (may be null for background jobs)
 * @param payload       purpose-built payload record; serialised to jsonb
 */
public record DomainEvent(
        UUID    eventId,
        String  eventType,
        String  aggregateType,
        UUID    aggregateId,
        Instant occurredAt,
        String  traceId,
        UUID    actorUserId,
        Object  payload
) {}

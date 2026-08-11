package com.fieldservice.platform.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the {@code outbox_event} table.
 *
 * <p>Infrastructure entity — does NOT extend BaseEntity (no version / updated_at columns)
 * and is NOT a ScopedEntity (the WO-005 poller reads it under service credentials, not user scope).
 *
 * <p>The {@code event_id} is provided by the caller from the {@link com.fieldservice.platform.api.DomainEvent}
 * record — it is a UUIDv7 generated before the transaction begins so the caller can carry it in
 * response bodies, metrics, and log lines.
 *
 * <p>Package-private: callers interact through {@link com.fieldservice.platform.api.DomainEventPublisher}.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Serialised JSON payload stored as PostgreSQL {@code jsonb}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
    }

    static OutboxEvent of(UUID eventId, String eventType, String aggregateType,
                          UUID aggregateId, String payloadJson, String traceId, UUID actorUserId) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = eventId;
        e.eventType = eventType;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.payload = payloadJson;
        e.traceId = traceId;
        e.actorUserId = actorUserId;
        return e;
    }

    UUID getEventId() { return eventId; }
    String getEventType() { return eventType; }
    String getAggregateType() { return aggregateType; }
    UUID getAggregateId() { return aggregateId; }
    String getPayload() { return payload; }
    String getTraceId() { return traceId; }
    UUID getActorUserId() { return actorUserId; }
    Instant getCreatedAt() { return createdAt; }
    Instant getPublishedAt() { return publishedAt; }
    int getAttemptCount() { return attemptCount; }
    String getLastError() { return lastError; }
}

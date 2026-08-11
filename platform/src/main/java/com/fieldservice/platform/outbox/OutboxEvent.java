package com.fieldservice.platform.outbox;

import com.fieldservice.platform.api.DomainEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing the {@code outbox_event} table.
 *
 * <p>Package-private: callers interact through {@link com.fieldservice.platform.api.DomainEventPublisher}
 * and {@link com.fieldservice.platform.api.DomainEvent} only.
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    protected OutboxEvent() {}

    static OutboxEvent from(DomainEvent event, String jsonPayload) {
        OutboxEvent e = new OutboxEvent();
        e.eventId       = event.eventId();
        e.eventType     = event.eventType();
        e.aggregateType = event.aggregateType();
        e.aggregateId   = event.aggregateId();
        e.payload       = jsonPayload;
        e.traceId       = event.traceId();
        e.actorUserId   = event.actorUserId();
        e.createdAt     = Instant.now();
        e.attemptCount  = 0;
        return e;
    }

    UUID    getEventId()         { return eventId; }
    String  getEventType()       { return eventType; }
    String  getAggregateType()   { return aggregateType; }
    UUID    getAggregateId()     { return aggregateId; }
    String  getPayload()         { return payload; }
    String  getTraceId()         { return traceId; }
    UUID    getActorUserId()     { return actorUserId; }
    Instant getCreatedAt()       { return createdAt; }
    Instant getPublishedAt()     { return publishedAt; }
    int     getAttemptCount()    { return attemptCount; }
    String  getLastError()       { return lastError; }
    Instant getNextAttemptAt()   { return nextAttemptAt; }
    Instant getDeadLetteredAt()  { return deadLetteredAt; }

    void markPublished(Instant at)          { this.publishedAt = at; }
    void markDeadLettered(Instant at)       { this.deadLetteredAt = at; }
    void recordFailure(String err, Instant nextAttempt) {
        this.attemptCount++;
        this.lastError = err;
        this.nextAttemptAt = nextAttempt;
    }
}

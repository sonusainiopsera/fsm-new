package com.fieldservice.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_dead_letter")
class NotificationDeadLetterEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String consumer;

    @Column(name = "failure_reason", nullable = false)
    private String failureReason;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationDeadLetterEntity() {}

    static NotificationDeadLetterEntity of(UUID id, UUID eventId, String consumer,
                                            String failureReason, String payloadHash,
                                            int attemptCount) {
        var e = new NotificationDeadLetterEntity();
        e.id            = id;
        e.eventId       = eventId;
        e.consumer      = consumer;
        e.failureReason = failureReason;
        e.payloadHash   = payloadHash;
        e.attemptCount  = attemptCount;
        e.createdAt     = Instant.now();
        return e;
    }

    UUID getId()           { return id; }
    UUID getEventId()      { return eventId; }
    String getConsumer()   { return consumer; }
    String getFailureReason() { return failureReason; }
    String getPayloadHash() { return payloadHash; }
    int getAttemptCount()  { return attemptCount; }
    Instant getCreatedAt() { return createdAt; }
}

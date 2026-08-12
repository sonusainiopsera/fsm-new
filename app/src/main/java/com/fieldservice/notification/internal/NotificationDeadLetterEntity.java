package com.fieldservice.notification.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Dead-letter quarantine record for notification fan-out failures (WO-196, AC-7).
 *
 * <p>Written when a consumer determines that a message has failed <em>deterministically</em>
 * (e.g. missing template, unresolvable recipient, malformed payload). The event is not
 * acknowledged as delivered; the dead-letter record provides an audit trail and a metric
 * hook.
 */
@Entity
@Table(name = "notification_dead_letter")
class NotificationDeadLetterEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer", nullable = false)
    private String consumer;

    @Column(name = "failure_reason", nullable = false)
    private String failureReason;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationDeadLetterEntity() {}

    static NotificationDeadLetterEntity create(UUID eventId, String consumer,
                                                String failureReason, String payloadHash,
                                                int attemptCount) {
        var e = new NotificationDeadLetterEntity();
        e.id = UuidV7.generate();
        e.eventId = eventId;
        e.consumer = consumer;
        e.failureReason = failureReason;
        e.payloadHash = payloadHash;
        e.attemptCount = attemptCount;
        return e;
    }

    UUID getEventId()        { return eventId; }
    String getConsumer()     { return consumer; }
    String getFailureReason(){ return failureReason; }
}

package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only delivery attempt audit row.
 *
 * <p>One row per delivery attempt. {@code recipientMask} contains the stable non-reversible
 * token; raw contact is never stored.
 */
@Entity
@Table(name = "notification_delivery_attempt")
class DeliveryAttemptEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(name = "adapter", nullable = false, length = 30)
    private String adapter;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_mask", nullable = false, length = 100)
    private String recipientMask;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private DeliveryOutcome outcome;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DeliveryAttemptEntity() {}

    static DeliveryAttemptEntity create(UUID eventId, NotificationChannel channel,
                                         String adapter, UUID recipientUserId,
                                         String recipientMask, int attemptNo,
                                         DeliveryOutcome outcome, String providerReference,
                                         Integer durationMs, String failureCode) {
        var e = new DeliveryAttemptEntity();
        e.id = UuidV7.generate();
        e.eventId = eventId;
        e.channel = channel;
        e.adapter = adapter;
        e.recipientUserId = recipientUserId;
        e.recipientMask = recipientMask;
        e.attemptNo = attemptNo;
        e.outcome = outcome;
        e.providerReference = providerReference;
        e.durationMs = durationMs;
        e.failureCode = failureCode;
        return e;
    }

    UUID getId()               { return id; }
    UUID getEventId()          { return eventId; }
    NotificationChannel getChannel() { return channel; }
    DeliveryOutcome getOutcome()     { return outcome; }
    UUID getRecipientUserId()  { return recipientUserId; }
}

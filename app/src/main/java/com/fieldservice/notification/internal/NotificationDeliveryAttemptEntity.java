package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery_attempt")
class NotificationDeliveryAttemptEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, updatable = false, length = 100)
    private String adapter;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_mask", nullable = false, updatable = false, length = 255)
    private String recipientMask;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryOutcome outcome;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationDeliveryAttemptEntity() {}

    static NotificationDeliveryAttemptEntity of(
            UUID id, UUID eventId, NotificationChannel channel, String adapter,
            UUID recipientUserId, String recipientMask, int attemptNo,
            DeliveryOutcome outcome, String providerReference, int durationMs,
            String failureCode) {
        var e = new NotificationDeliveryAttemptEntity();
        e.id = id;
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
        e.createdAt = Instant.now();
        return e;
    }

    UUID getId()               { return id; }
    UUID getEventId()          { return eventId; }
    NotificationChannel getChannel() { return channel; }
    String getAdapter()        { return adapter; }
    UUID getRecipientUserId()  { return recipientUserId; }
    String getRecipientMask()  { return recipientMask; }
    int getAttemptNo()         { return attemptNo; }
    DeliveryOutcome getOutcome() { return outcome; }
    String getProviderReference() { return providerReference; }
    int getDurationMs()        { return durationMs; }
    String getFailureCode()    { return failureCode; }
    Instant getCreatedAt()     { return createdAt; }
}

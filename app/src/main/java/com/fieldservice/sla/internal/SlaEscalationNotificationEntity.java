package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a single SLA escalation notification attempt.
 *
 * <p>The unique index on {@code (event_id, recipient_user_id, channel)} prevents
 * duplicate delivery on outbox redelivery (idempotency gate).
 *
 * <p>{@code masked_destination} holds a stable non-reversible token; raw contact
 * details are never persisted — only passed transiently to the provider adapter.
 */
@Entity
@Table(name = "sla_escalation_notification")
class SlaEscalationNotificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_role", nullable = false, length = 20)
    private String recipientRole;

    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "outcome_reason", length = 200)
    private String outcomeReason;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "masked_destination", nullable = false, length = 100)
    private String maskedDestination;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SlaEscalationNotificationEntity() {}

    static SlaEscalationNotificationEntity create(UUID eventId, UUID workOrderId,
                                                   UUID recipientUserId, String recipientRole,
                                                   String channel, String eventType,
                                                   int attemptCount, String outcome,
                                                   String outcomeReason, String providerMessageId,
                                                   String maskedDestination) {
        var e = new SlaEscalationNotificationEntity();
        e.id                 = UuidV7.generate();
        e.eventId            = eventId;
        e.workOrderId        = workOrderId;
        e.recipientUserId    = recipientUserId;
        e.recipientRole      = recipientRole;
        e.channel            = channel;
        e.eventType          = eventType;
        e.attemptCount       = attemptCount;
        e.outcome            = outcome;
        e.outcomeReason      = outcomeReason;
        e.providerMessageId  = providerMessageId;
        e.maskedDestination  = maskedDestination;
        return e;
    }

    UUID getId()                 { return id; }
    UUID getEventId()            { return eventId; }
    UUID getWorkOrderId()        { return workOrderId; }
    UUID getRecipientUserId()    { return recipientUserId; }
    String getRecipientRole()    { return recipientRole; }
    String getChannel()          { return channel; }
    String getEventType()        { return eventType; }
    int getAttemptCount()        { return attemptCount; }
    String getOutcome()          { return outcome; }
    String getOutcomeReason()    { return outcomeReason; }
    String getProviderMessageId(){ return providerMessageId; }
    String getMaskedDestination(){ return maskedDestination; }
    Instant getCreatedAt()       { return createdAt; }
    Instant getUpdatedAt()       { return updatedAt; }
}

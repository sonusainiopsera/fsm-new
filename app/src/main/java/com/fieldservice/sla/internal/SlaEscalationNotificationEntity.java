package com.fieldservice.sla.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Records one delivery attempt per (event_id, recipient_user_id, channel).
 * The unique index on that triple enforces idempotency: a redelivered outbox event
 * never produces a second row for the same recipient and channel.
 */
@Entity
@Table(name = "sla_escalation_notification")
class SlaEscalationNotificationEntity {

    /** Controlled outcome vocabulary matching the DB check constraint. */
    static final String OUTCOME_SENT       = "SENT";
    static final String OUTCOME_FAILED     = "FAILED";
    static final String OUTCOME_DEGRADED   = "DEGRADED";
    static final String OUTCOME_SKIPPED    = "SKIPPED";
    static final String OUTCOME_SUPPRESSED = "SUPPRESSED";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_role", nullable = false, length = 50)
    private String recipientRole;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(name = "outcome_reason", length = 100)
    private String outcomeReason;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "masked_destination", length = 255)
    private String maskedDestination;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SlaEscalationNotificationEntity() {}

    static SlaEscalationNotificationEntity create(UUID eventId, UUID workOrderId,
                                                    UUID recipientUserId, String recipientRole,
                                                    String channel, int attemptCount,
                                                    String outcome, String outcomeReason,
                                                    String providerMessageId, String maskedDestination) {
        SlaEscalationNotificationEntity e = new SlaEscalationNotificationEntity();
        e.id               = UuidV7.generate();
        e.eventId          = eventId;
        e.workOrderId      = workOrderId;
        e.recipientUserId  = recipientUserId;
        e.recipientRole    = recipientRole;
        e.channel          = channel;
        e.attemptCount     = attemptCount;
        e.outcome          = outcome;
        e.outcomeReason    = outcomeReason;
        e.providerMessageId = providerMessageId;
        e.maskedDestination = maskedDestination;
        Instant now        = Instant.now();
        e.createdAt        = now;
        e.updatedAt        = now;
        return e;
    }

    UUID   getId()               { return id; }
    UUID   getEventId()          { return eventId; }
    UUID   getWorkOrderId()      { return workOrderId; }
    UUID   getRecipientUserId()  { return recipientUserId; }
    String getRecipientRole()    { return recipientRole; }
    String getChannel()          { return channel; }
    int    getAttemptCount()     { return attemptCount; }
    String getOutcome()          { return outcome; }
    String getOutcomeReason()    { return outcomeReason; }
    String getProviderMessageId(){ return providerMessageId; }
    String getMaskedDestination(){ return maskedDestination; }
}

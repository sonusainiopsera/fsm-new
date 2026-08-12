package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Package-private JPA repository for {@link SlaEscalationNotificationEntity}.
 */
interface SlaEscalationNotificationRepository extends JpaRepository<SlaEscalationNotificationEntity, UUID> {

    /** Returns true if an idempotency row already exists for this (event, recipient, channel). */
    boolean existsByEventIdAndRecipientUserIdAndChannel(UUID eventId, UUID recipientUserId, String channel);

    /**
     * Suppression window check: true if a SENT notification was sent to this recipient on
     * this channel for this work order within the given window.
     */
    @Query("""
            SELECT COUNT(n) > 0 FROM SlaEscalationNotificationEntity n
            WHERE n.workOrderId       = :workOrderId
              AND n.recipientUserId   = :recipientUserId
              AND n.channel           = :channel
              AND n.outcome           = 'SENT'
              AND n.createdAt        >= :windowStart
            """)
    boolean existsRecentSentInWindow(@Param("workOrderId")     UUID workOrderId,
                                     @Param("recipientUserId") UUID recipientUserId,
                                     @Param("channel")         String channel,
                                     @Param("windowStart")     Instant windowStart);
}

package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
interface SlaEscalationNotificationRepository extends JpaRepository<SlaEscalationNotificationEntity, UUID> {

    Optional<SlaEscalationNotificationEntity> findByEventIdAndRecipientUserIdAndChannel(
            UUID eventId, UUID recipientUserId, String channel);

    @Query("""
            SELECT COUNT(n) > 0 FROM SlaEscalationNotificationEntity n
            WHERE n.workOrderId = :workOrderId
              AND n.recipientUserId = :recipientUserId
              AND n.channel = :channel
              AND n.outcome IN ('SENT', 'DEGRADED')
              AND n.createdAt >= :since
            """)
    boolean existsRecentNotification(
            @Param("workOrderId")      UUID workOrderId,
            @Param("recipientUserId")  UUID recipientUserId,
            @Param("channel")          String channel,
            @Param("since")            Instant since);
}

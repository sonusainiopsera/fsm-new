package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttemptEntity, UUID> {

    boolean existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
            UUID eventId, NotificationChannel channel, UUID recipientUserId, DeliveryOutcome outcome);

    @Query("SELECT COALESCE(MAX(a.attemptNo), 0) FROM DeliveryAttemptEntity a " +
           "WHERE a.eventId = :eventId AND a.channel = :channel AND a.recipientUserId = :recipientUserId")
    int maxAttemptNo(@Param("eventId") UUID eventId,
                     @Param("channel") NotificationChannel channel,
                     @Param("recipientUserId") UUID recipientUserId);
}

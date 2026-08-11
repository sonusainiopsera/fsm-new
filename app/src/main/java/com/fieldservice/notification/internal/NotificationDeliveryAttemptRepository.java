package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface NotificationDeliveryAttemptRepository
        extends JpaRepository<NotificationDeliveryAttemptEntity, UUID> {

    Optional<NotificationDeliveryAttemptEntity> findByEventIdAndChannelAndRecipientUserId(
            UUID eventId, NotificationChannel channel, UUID recipientUserId);
}

package com.fieldservice.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface InAppNotificationRepository extends JpaRepository<InAppNotificationEntity, UUID> {

    boolean existsByEventIdAndRecipientUserId(UUID eventId, UUID recipientUserId);
}

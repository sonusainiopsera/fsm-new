package com.fieldservice.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Package-private repository for notification dead-letter records.
 */
interface NotificationDeadLetterRepository extends JpaRepository<NotificationDeadLetterEntity, UUID> {

    boolean existsByEventIdAndConsumer(UUID eventId, String consumer);
}

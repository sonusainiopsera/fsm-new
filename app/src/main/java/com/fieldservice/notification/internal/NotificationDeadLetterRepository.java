package com.fieldservice.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface NotificationDeadLetterRepository extends JpaRepository<NotificationDeadLetterEntity, UUID> {
}

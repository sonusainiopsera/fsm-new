package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface NotificationTemplateRepository extends JpaRepository<NotificationTemplateEntity, UUID> {

    Optional<NotificationTemplateEntity> findByTemplateKeyAndChannelAndLocaleAndActiveTrue(
            String templateKey, NotificationChannel channel, String locale);
}

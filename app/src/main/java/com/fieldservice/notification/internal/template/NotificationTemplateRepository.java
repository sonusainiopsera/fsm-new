package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Package-private repository for notification templates.
 *
 * <p>Only one active version per (template_key, channel, locale) is enforced by
 * the partial unique index in V68. This repository exposes only that active-version
 * lookup to keep the template fetch path simple.
 */
interface NotificationTemplateRepository extends JpaRepository<NotificationTemplateEntity, UUID> {

    Optional<NotificationTemplateEntity> findByTemplateKeyAndChannelAndLocaleAndActiveTrue(
            String templateKey, NotificationChannel channel, String locale);
}

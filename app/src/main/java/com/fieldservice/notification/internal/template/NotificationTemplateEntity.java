package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code notification_template} table.
 *
 * <p>Package-private: accessed only through {@link StrictParameterTemplateRenderer}.
 */
@Entity
@Table(name = "notification_template")
class NotificationTemplateEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "template_key", nullable = false)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "subject_template")
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false)
    private String bodyTemplate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationTemplateEntity() {}

    String getTemplateKey()    { return templateKey; }
    NotificationChannel getChannel() { return channel; }
    String getLocale()         { return locale; }
    int getVersion()           { return version; }
    boolean isActive()         { return active; }
    String getSubjectTemplate(){ return subjectTemplate; }
    String getBodyTemplate()   { return bodyTemplate; }
}

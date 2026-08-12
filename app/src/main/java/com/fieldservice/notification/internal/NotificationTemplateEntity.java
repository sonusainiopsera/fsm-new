package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_template")
class NotificationTemplateEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "template_key", nullable = false)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
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

    UUID getId()              { return id; }
    String getTemplateKey()   { return templateKey; }
    NotificationChannel getChannel() { return channel; }
    String getLocale()        { return locale; }
    int getVersion()          { return version; }
    boolean isActive()        { return active; }
    String getSubjectTemplate() { return subjectTemplate; }
    String getBodyTemplate()  { return bodyTemplate; }
}

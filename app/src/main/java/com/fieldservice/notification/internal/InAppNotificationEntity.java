package com.fieldservice.notification.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable in-app notification row written by {@link InAppFallbackAdapter}.
 *
 * <p>This row is the persistence guarantee that backs the degraded-fallback contract.
 * SSE push is best-effort and may not reach the recipient if they are offline; this
 * row ensures they see the notification on next login.
 */
@Entity
@Table(name = "in_app_notification")
class InAppNotificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InAppNotificationEntity() {}

    static InAppNotificationEntity create(UUID recipientUserId, UUID eventId,
                                           String category, String title,
                                           String body, String severity) {
        var e = new InAppNotificationEntity();
        e.id = UuidV7.generate();
        e.recipientUserId = recipientUserId;
        e.eventId = eventId;
        e.category = category;
        e.title = title;
        e.body = body;
        e.severity = severity;
        return e;
    }

    UUID getId()                { return id; }
    UUID getRecipientUserId()   { return recipientUserId; }
    UUID getEventId()           { return eventId; }
    String getTitle()           { return title; }
    String getBody()            { return body; }
    String getCategory()        { return category; }
    String getSeverity()        { return severity; }
    Instant getCreatedAt()      { return createdAt; }
}

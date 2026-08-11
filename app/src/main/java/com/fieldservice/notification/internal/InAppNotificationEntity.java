package com.fieldservice.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "in_app_notification")
class InAppNotificationEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false, length = 100)
    private String category;

    @Column(nullable = false, updatable = false, length = 500)
    private String title;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InAppNotificationEntity() {}

    static InAppNotificationEntity of(UUID id, UUID recipientUserId, UUID eventId,
                                       String category, String title, String body, String severity) {
        var e = new InAppNotificationEntity();
        e.id = id;
        e.recipientUserId = recipientUserId;
        e.eventId = eventId;
        e.category = category;
        e.title = title;
        e.body = body;
        e.severity = severity;
        e.createdAt = Instant.now();
        return e;
    }

    UUID getId()              { return id; }
    UUID getRecipientUserId() { return recipientUserId; }
    UUID getEventId()         { return eventId; }
    String getCategory()      { return category; }
    String getTitle()         { return title; }
    String getBody()          { return body; }
    String getSeverity()      { return severity; }
    Instant getReadAt()       { return readAt; }
    Instant getCreatedAt()    { return createdAt; }
}

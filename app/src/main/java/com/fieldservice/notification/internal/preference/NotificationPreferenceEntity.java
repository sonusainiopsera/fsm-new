package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for a single per-user notification channel preference.
 *
 * <p>Unique on (user_id, category, channel). The category column stores the
 * {@link com.fieldservice.notification.api.NotificationCategory} enum name as
 * a string, validated via a database CHECK constraint in V64.
 *
 * <p>{@code @Audited} wires Hibernate Envers to record every INSERT/UPDATE/DELETE
 * to {@code notification_preference_aud} in the same transaction.
 */
@Audited
@Entity
@Table(name = "notification_preference")
class NotificationPreferenceEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled = true;

    @Version
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected NotificationPreferenceEntity() {}

    static NotificationPreferenceEntity create(UUID userId, String category,
                                                NotificationChannel channel, boolean enabled) {
        NotificationPreferenceEntity e = new NotificationPreferenceEntity();
        e.id        = UuidV7.generate();
        e.userId    = userId;
        e.category  = category;
        e.channel   = channel;
        e.enabled   = enabled;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    void setEnabled(boolean enabled) {
        this.enabled   = enabled;
        this.updatedAt = Instant.now();
    }

    UUID                getId()       { return id; }
    UUID                getUserId()   { return userId; }
    String              getCategory() { return category; }
    NotificationChannel getChannel()  { return channel; }
    boolean             isEnabled()   { return enabled; }
    Integer             getVersion()  { return version; }
    Instant             getCreatedAt(){ return createdAt; }
    Instant             getUpdatedAt(){ return updatedAt; }
}

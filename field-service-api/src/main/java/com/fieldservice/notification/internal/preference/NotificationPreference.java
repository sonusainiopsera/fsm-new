package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-user notification preference for a specific category and delivery channel.
 *
 * <p>The combination of {@code (user_id, category, channel)} is unique: each row
 * represents a single explicit on/off decision for one channel within one
 * notification category.
 *
 * <p>Rows missing from the table are interpreted as "default-on" by the service
 * layer — that is, all channels are enabled unless explicitly overridden.
 *
 * <p>The entity is fully audited by Hibernate Envers; every INSERT / UPDATE /
 * DELETE writes a corresponding row to {@code notification_preference_AUD}.
 */
@Entity
@Audited
@Table(
        name = "notification_preference",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_preference_user_category_channel",
                columnNames = {"user_id", "category", "channel"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreference {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The user whose preference this represents.
     * References {@code app_user(id)}, enforced at the database level.
     */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The event category this preference applies to. Stored as a VARCHAR string. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false)
    private NotificationCategory category;

    /** The delivery channel this preference applies to. Stored as a VARCHAR string. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    /** Whether notifications should be sent via this channel for this category. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * Optimistic-locking version counter.
     * A {@link org.springframework.orm.ObjectOptimisticLockingFailureException} is
     * raised on concurrent modification, surfaced as HTTP 409 by the global handler.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        var now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Create a new preference with enabled defaulting to {@code true}.
     */
    public static NotificationPreference create(UUID userId, NotificationCategory category,
                                                NotificationChannel channel, boolean enabled) {
        var pref = new NotificationPreference();
        pref.userId = userId;
        pref.category = category;
        pref.channel = channel;
        pref.enabled = enabled;
        return pref;
    }
}

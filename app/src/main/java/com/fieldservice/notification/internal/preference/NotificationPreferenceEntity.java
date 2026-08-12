package com.fieldservice.notification.internal.preference;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Stores an explicit per-user notification channel preference.
 *
 * <p>Absence of a row for a (user_id, category, channel) triple is semantically
 * equivalent to {@code enabled = true} (default-on). Only explicit overrides are stored.
 *
 * <p>Annotated {@link Audited} so every mutation produces an immutable revision row
 * in {@code notification_preference_aud} capturing the actor, timestamp and changed value.
 */
@Audited
@Entity
@Table(
    name = "notification_preference",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_notification_preference_key",
        columnNames = {"user_id", "category", "channel"}
    )
)
class NotificationPreferenceEntity extends BaseEntity implements ScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "category", nullable = false, updatable = false, length = 60)
    private String category;

    @Column(name = "channel", nullable = false, updatable = false, length = 10)
    private String channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected NotificationPreferenceEntity() {}

    static NotificationPreferenceEntity create(UUID userId, String category,
                                                String channel, boolean enabled) {
        var e = new NotificationPreferenceEntity();
        e.userId   = userId;
        e.category = category;
        e.channel  = channel;
        e.enabled  = enabled;
        return e;
    }

    UUID getUserId()    { return userId; }
    String getCategory() { return category; }
    String getChannel()  { return channel; }
    boolean isEnabled()  { return enabled; }

    void setEnabled(boolean enabled) { this.enabled = enabled; }
}

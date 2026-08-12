package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.api.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for notification channel preferences.
 *
 * <p>Package-private: consumers of this data go through
 * {@link com.fieldservice.notification.api.NotificationPreferenceService}.
 */
interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, UUID> {

    /** Returns all explicit preference rows for a user, across all categories. */
    List<NotificationPreferenceEntity> findByUserId(UUID userId);

    /** Returns all explicit preference rows for a (user, category) pair. */
    List<NotificationPreferenceEntity> findByUserIdAndCategory(UUID userId, String category);

    /** Looks up a single (user, category, channel) row for upsert logic. */
    Optional<NotificationPreferenceEntity> findByUserIdAndCategoryAndChannel(
            UUID userId, String category, NotificationChannel channel);
}

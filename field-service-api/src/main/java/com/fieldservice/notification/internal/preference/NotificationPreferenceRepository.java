package com.fieldservice.notification.internal.preference;

import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link NotificationPreference}.
 *
 * <p>All query methods include a mandatory {@code userId} predicate to prevent
 * cross-tenant data leakage at the persistence layer (defence in depth on top of
 * the service-level access scope enforcement).
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    /**
     * Look up a single preference by its natural key.
     */
    Optional<NotificationPreference> findByUserIdAndCategoryAndChannel(
            UUID userId,
            NotificationCategory category,
            NotificationChannel channel
    );

    /**
     * Retrieve all preferences for a user within one notification category.
     * Used by {@code resolveEffective} to determine which channels are active.
     */
    List<NotificationPreference> findAllByUserIdAndCategory(
            UUID userId,
            NotificationCategory category
    );

    /**
     * Retrieve all preferences for a user (no pagination). Used to build the full
     * effective set in-memory before applying page slicing.
     */
    List<NotificationPreference> findAllByUserId(UUID userId);

    /**
     * Retrieve a page of all preferences for a user.
     * The {@code userId} predicate is mandatory — callers must always supply it.
     */
    Page<NotificationPreference> findAllByUserId(UUID userId, Pageable pageable);

    /**
     * Check existence without loading the entity.
     */
    boolean existsByUserIdAndCategoryAndChannel(
            UUID userId,
            NotificationCategory category,
            NotificationChannel channel
    );
}

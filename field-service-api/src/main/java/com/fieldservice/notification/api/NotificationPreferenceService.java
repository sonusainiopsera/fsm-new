package com.fieldservice.notification.api;

import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;
import com.fieldservice.notification.web.dto.NotificationPreferenceDto;
import com.fieldservice.notification.web.dto.UpsertPreferencesRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;

/**
 * Public module API for managing per-user notification preferences.
 *
 * <p>This interface is the only surface other modules should depend on.
 * Implementation lives in the {@code internal} package and must not be
 * referenced directly from outside the notification module.
 */
public interface NotificationPreferenceService {

    /**
     * Resolve the set of channels that are currently active for a given user and
     * notification category, applying default-on semantics.
     *
     * <ul>
     *   <li>If no explicit preferences exist → all channels are returned (default-on).</li>
     *   <li>If explicit preferences exist → only channels marked {@code enabled=true} are returned.</li>
     * </ul>
     *
     * <p>This method <strong>never throws</strong>. On any infrastructure failure it
     * logs a warning and falls back to returning all channels.
     *
     * @param userId   the user whose preferences to consult
     * @param category the notification category being dispatched
     * @return immutable set of active channels; never {@code null}
     */
    Set<NotificationChannel> resolveEffective(UUID userId, NotificationCategory category);

    /**
     * Return a paginated list of explicit preferences for {@code userId}.
     *
     * <p>Access control is enforced inside the implementation:
     * a non-admin caller may only query their own preferences.
     *
     * @param userId   the target user
     * @param callerId the authenticated caller's UUID
     * @param isAdmin  whether the caller holds the ADMIN role
     * @param pageable pagination and sort parameters (max 50 per page enforced by controller)
     * @return paged DTOs with {@link com.fieldservice.notification.web.dto.NotificationPreferenceDto.PreferenceSource#EXPLICIT}
     * @throws org.springframework.security.access.AccessDeniedException if scope check fails
     */
    Page<NotificationPreferenceDto> getPreferences(
            UUID userId,
            UUID callerId,
            boolean isAdmin,
            Pageable pageable
    );

    /**
     * Bulk-upsert notification preferences for {@code userId}.
     *
     * <p>For each item in {@code request}:
     * <ul>
     *   <li>If a row already exists for (userId, category, channel) → update {@code enabled}.</li>
     *   <li>Otherwise → create a new row.</li>
     * </ul>
     *
     * <p>All upserts execute within a single transaction. On optimistic-lock collision
     * the transaction is rolled back and the caller receives HTTP 409.
     *
     * <p>Access control is enforced inside the implementation.
     *
     * @param userId   the target user
     * @param callerId the authenticated caller's UUID
     * @param isAdmin  whether the caller holds the ADMIN role
     * @param request  validated list of preference items to upsert
     * @param pageable used to return the updated preference list after the upsert
     * @return paged view of all preferences for the user after the operation
     * @throws org.springframework.security.access.AccessDeniedException if scope check fails
     */
    Page<NotificationPreferenceDto> upsertPreferences(
            UUID userId,
            UUID callerId,
            boolean isAdmin,
            UpsertPreferencesRequest request,
            Pageable pageable
    );
}

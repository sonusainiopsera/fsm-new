package com.fieldservice.notification.api;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public API for per-user notification channel preferences.
 *
 * <p>Fan-out consumers call {@link #resolveEffective} before dispatch.
 * The contract is default-on: when no preference row exists for a user
 * and category, all configured channels are treated as enabled so fan-out
 * is never blocked by missing configuration.
 *
 * <p>Resolution errors must be caught by callers and treated as default-on
 * (see the interface contract on {@link #resolveEffective}).
 */
public interface NotificationPreferenceService {

    /**
     * Returns the effective set of channels for the given user and category.
     *
     * <p>Default-on rule: if the user has no preference row for this category,
     * all channels in {@link NotificationChannel} are returned. If the user
     * has explicit rows, only channels with {@code enabled=true} are returned.
     *
     * <p>Callers must catch all exceptions and fall back to all channels —
     * a resolution failure must never block a notification from being sent.
     *
     * @param userId   the recipient user
     * @param category the notification category
     * @return effective channel set; never null, may be empty if user opted out of all
     */
    Set<NotificationChannel> resolveEffective(UUID userId, String category);

    /**
     * Returns all stored preference rows for the given user.
     * Rows that are absent (default-on) are not listed here; callers must
     * use the {@code source} field in the response to distinguish them.
     */
    List<PreferenceEntryView> listPreferences(UUID userId);

    /**
     * Upserts explicit preferences for the given user.
     *
     * @param userId   the user being configured
     * @param entries  list of category + channel + enabled triples
     * @param actorId  the user performing the change (for audit attribution)
     * @return the full effective preference list after applying the changes
     */
    List<PreferenceEntryView> upsertPreferences(UUID userId,
                                                 List<PreferenceEntry> entries,
                                                 UUID actorId);

    /**
     * Represents one (category, channel) preference row as seen by a caller.
     *
     * @param category the notification category
     * @param channel  the channel
     * @param enabled  whether the channel is enabled for this category
     * @param source   {@code DEFAULT} when no explicit row exists (default-on applied),
     *                 {@code EXPLICIT} when the user has stored a choice
     */
    record PreferenceEntryView(
            String category,
            NotificationChannel channel,
            boolean enabled,
            PreferenceSource source) {}

    /**
     * A request to set a (category, channel) preference.
     */
    record PreferenceEntry(String category, NotificationChannel channel, boolean enabled) {}

    /** Distinguishes an unset default-on entry from a stored explicit choice. */
    enum PreferenceSource { DEFAULT, EXPLICIT }

    /** All channels — returned for default-on resolution. */
    Set<NotificationChannel> ALL_CHANNELS = EnumSet.allOf(NotificationChannel.class);
}

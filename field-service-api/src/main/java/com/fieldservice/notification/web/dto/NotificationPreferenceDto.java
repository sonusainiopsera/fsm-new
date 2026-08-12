package com.fieldservice.notification.web.dto;

import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;

/**
 * DTO representing one notification preference entry in an API response.
 *
 * @param category the event category
 * @param channel  the delivery channel
 * @param enabled  whether the channel is active for this category
 * @param source   {@link PreferenceSource#EXPLICIT} when the user has saved an explicit
 *                 setting; {@link PreferenceSource#DEFAULT} when the value is inferred
 *                 from the system default (enabled for all channels)
 */
public record NotificationPreferenceDto(
        NotificationCategory category,
        NotificationChannel channel,
        boolean enabled,
        PreferenceSource source
) {

    /**
     * Indicates whether the preference value was explicitly saved by the user or
     * inferred from the system default.
     */
    public enum PreferenceSource {
        /**
         * No explicit preference has been saved; the system default applies
         * (all channels enabled).
         */
        DEFAULT,

        /**
         * The user has explicitly saved a preference that overrides the default.
         */
        EXPLICIT
    }
}

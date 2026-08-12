package com.fieldservice.notification.web.dto;

import com.fieldservice.notification.domain.NotificationCategory;
import com.fieldservice.notification.domain.NotificationChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for bulk-upserting notification preferences.
 *
 * <p>Each item in {@code preferences} addresses one (category, channel) pair.
 * Duplicate pairs in the same request are idempotent — the last one wins within
 * the transaction.
 *
 * @param preferences non-empty list of preference items to create or update
 */
public record UpsertPreferencesRequest(
        @Valid
        @NotEmpty(message = "preferences must contain at least one item")
        @Size(max = 50, message = "preferences may contain at most 50 items per request")
        List<@Valid PreferenceItem> preferences
) {

    /**
     * A single (category, channel, enabled) triple.
     *
     * @param category the notification category to configure
     * @param channel  the delivery channel to configure
     * @param enabled  {@code true} to enable, {@code false} to disable
     */
    public record PreferenceItem(
            @NotNull(message = "category is required")
            NotificationCategory category,

            @NotNull(message = "channel is required")
            NotificationChannel channel,

            @NotNull(message = "enabled is required")
            Boolean enabled
    ) {}
}

package com.fieldservice.notification.web;

import com.fieldservice.notification.api.NotificationChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for PUT /api/v1/users/{userId}/notification-preferences.
 *
 * <p>FAIL_ON_UNKNOWN_PROPERTIES is enabled globally (Jackson configuration) so any
 * extra fields in the request body return 400.
 *
 * @param preferences list of preference entries to upsert; max 50 per request
 */
public record NotificationPreferenceRequest(
        @NotEmpty
        @Size(max = 50)
        @Valid
        List<PreferenceEntry> preferences) {

    /**
     * One (category, channel, enabled) triple in the upsert body.
     *
     * @param category allow-listed notification category name
     * @param channel  allow-listed channel name
     * @param enabled  whether the channel should be enabled for this category
     */
    public record PreferenceEntry(
            @NotBlank @Size(max = 50) String category,
            @NotNull NotificationChannel channel,
            boolean enabled) {}
}

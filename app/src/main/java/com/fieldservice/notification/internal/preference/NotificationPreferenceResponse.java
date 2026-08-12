package com.fieldservice.notification.internal.preference;

/**
 * Response DTO for a single notification preference entry.
 *
 * <p>{@code source} distinguishes an unset default-on entry (DEFAULT) from a stored
 * explicit choice (EXPLICIT), so clients can show which preferences have been customised.
 */
public record NotificationPreferenceResponse(
        String category,
        String channel,
        boolean enabled,
        Source source
) {
    public enum Source { DEFAULT, EXPLICIT }
}

package com.fieldservice.notification.api;

import java.util.UUID;

/**
 * Immutable descriptor for a notification to be delivered.
 *
 * <p>{@code recipientContact} is the raw PII value (email address, phone number, FCM token,
 * etc.). It is passed directly to the provider adapter and must NEVER be stored, logged, or
 * surfaced in any API response. The dispatcher replaces it with a stable masked token for
 * all persistence operations.
 */
public record NotificationRequest(
        UUID eventId,
        NotificationChannel channel,
        UUID recipientUserId,
        String recipientContact,
        String idempotencyKey,
        String category,
        String title,
        String body,
        String severity
) {
    public NotificationRequest {
        if (eventId == null)         throw new IllegalArgumentException("eventId must not be null");
        if (channel == null)         throw new IllegalArgumentException("channel must not be null");
        if (recipientUserId == null) throw new IllegalArgumentException("recipientUserId must not be null");
    }
}

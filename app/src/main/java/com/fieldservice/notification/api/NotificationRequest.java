package com.fieldservice.notification.api;

import java.util.UUID;

/**
 * Immutable request to deliver one notification. The {@code recipientContact} field
 * carries the raw PII value (email address, phone number, etc.) — it must never be
 * logged or persisted; only a masked token derived from it may appear in storage or logs.
 */
public record NotificationRequest(
        UUID   eventId,
        NotificationChannel channel,
        UUID   recipientUserId,
        String recipientContact,
        String subject,
        String body,
        String category,
        String severity
) {}

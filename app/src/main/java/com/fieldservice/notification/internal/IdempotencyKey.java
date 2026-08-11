package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationChannel;

import java.util.UUID;

/** Derives a stable idempotency key from a dispatch triple. No framework dependency. */
public final class IdempotencyKey {

    private IdempotencyKey() {}

    public static String derive(UUID eventId, NotificationChannel channel, UUID recipientUserId) {
        return eventId + "|" + channel.name() + "|" + recipientUserId;
    }
}

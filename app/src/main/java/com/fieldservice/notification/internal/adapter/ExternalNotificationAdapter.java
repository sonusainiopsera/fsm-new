package com.fieldservice.notification.internal.adapter;

import com.fieldservice.notification.api.NotificationRequest;

/**
 * Strategy interface for outbound provider delivery.
 *
 * <p>Implementations must throw {@link com.fieldservice.notification.internal.RetryableNotificationException}
 * for transient provider errors (5xx, timeout, 429) and
 * {@link com.fieldservice.notification.internal.PermanentNotificationException} for permanent
 * failures (4xx other than 429, invalid recipient). A normal return means the provider
 * accepted the message; the returned string is the provider's reference identifier
 * (may be null for stub adapters).
 */
public interface ExternalNotificationAdapter {

    String send(NotificationRequest request);

    String adapterName();
}

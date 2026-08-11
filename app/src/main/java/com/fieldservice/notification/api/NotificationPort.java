package com.fieldservice.notification.api;

/**
 * Vendor-agnostic notification delivery port.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>Idempotency per {@code (eventId, channel, recipientUserId)}</li>
 *   <li>Durable in-app fallback when the circuit breaker is open or the provider fails</li>
 *   <li>No raw PII stored — recipient masked before persistence</li>
 * </ul>
 *
 * <p>The production implementation is restricted to the {@code worker} Spring profile.
 */
public interface NotificationPort {

    /**
     * Delivers the notification described by {@code request}.
     *
     * @return the outcome of the delivery attempt; never {@code null}
     */
    DeliveryOutcome send(NotificationRequest request);
}

package com.fieldservice.notification.internal.consumer;

import com.fieldservice.platform.api.DomainEvent;

/**
 * Common interface for notification consumers that subscribe to outbox events.
 *
 * <p>Each implementation handles a specific event type, resolves recipients, renders
 * a versioned template, and dispatches via {@link com.fieldservice.notification.api.NotificationPort}.
 * All implementations run on the {@code worker} profile and are idempotent.
 */
public interface NotificationEventConsumer {

    /** The outbox event type string this consumer handles. */
    String supportedEventType();

    /** Consumer identifier used as the idempotency guard consumer name. */
    String consumerName();

    /**
     * Processes the event: resolve recipients, render template, dispatch, record attempt.
     * Must be called within an active transaction.
     */
    void consume(DomainEvent event) throws Exception;
}

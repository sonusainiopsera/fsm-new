package com.fieldservice.platform.outbox;

import com.fieldservice.platform.api.DomainEvent;

/**
 * Handles a single in-process domain event delivered by the outbox poller.
 *
 * <p>Implementations must be idempotent: the at-least-once delivery contract means the
 * same event may be delivered more than once under crash-recovery scenarios.  The platform
 * consumer-idempotency guard ({@code processed_event} table) prevents duplicate side effects
 * when the guard is used, but handlers that do not use the guard must be idempotent
 * by other means.
 *
 * <p>Register implementations as Spring beans; the outbox poller collects them via
 * {@code List<EventHandler>} injection and routes by {@link #supportedEventType()}.
 */
public interface EventHandler {

    /**
     * @return the {@code eventType} value this handler handles (e.g. {@code "WORK_ORDER_ASSIGNED"})
     */
    String supportedEventType();

    /**
     * Handles the event.  Called inside the poller's claim transaction; a thrown exception
     * triggers retry backoff and increments the attempt count.
     *
     * @param event the domain event to handle
     * @throws Exception any exception causes the event to be retried according to backoff policy
     */
    void handle(DomainEvent event) throws Exception;
}

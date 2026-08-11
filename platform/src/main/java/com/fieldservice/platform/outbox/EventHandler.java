package com.fieldservice.platform.outbox;

/**
 * Contract for in-process outbox event consumers.
 *
 * <p>Handlers are discovered by the outbox poller through Spring's bean registry and
 * routed by the string returned from {@link #getSupportedEventType()}.
 *
 * <p><strong>Idempotency contract:</strong> Every handler MUST call
 * {@code IdempotencyGuard.claimEvent(ctx.eventId(), handlerName)} as the first operation
 * before any side effect, and return without performing side effects if the guard returns
 * {@code false} (already processed). This makes at-least-once delivery safe.
 *
 * <p>Exactly-once delivery is NOT guaranteed and must not be assumed.
 */
public interface EventHandler {

    /** Returns the {@code event_type} string this handler processes. */
    String getSupportedEventType();

    /**
     * Processes the event described by {@code ctx}.
     *
     * <p>Runs inside the outbox claim transaction. An unchecked exception causes the
     * attempt to be recorded and the event rescheduled with jittered backoff.
     * After the configured maximum attempts the event moves to dead-letter state.
     */
    void handle(EventHandlerContext ctx) throws Exception;
}

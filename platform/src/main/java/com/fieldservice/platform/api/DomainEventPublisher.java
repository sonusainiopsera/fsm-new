package com.fieldservice.platform.api;

/**
 * Platform-owned seam for appending domain events to the transactional outbox.
 *
 * <p>The implementation uses {@code @Transactional(propagation = MANDATORY)}, which
 * guarantees that:
 * <ul>
 *   <li>The outbox row is written in the <em>caller's</em> existing transaction — the
 *       state change, its Envers revision, and the event all commit or roll back together.</li>
 *   <li>Calling {@code publish} outside an active transaction throws immediately rather
 *       than silently opening a separate transaction that would break atomicity.</li>
 * </ul>
 *
 * <p>Payload rules enforced at publish time:
 * <ul>
 *   <li>{@code @Restricted} fields must be null — violation throws and rolls back.</li>
 *   <li>{@code @Confidential} fields are masked in-place before serialisation.</li>
 *   <li>Serialised payload must not exceed the configured size bound.</li>
 * </ul>
 *
 * <p>This interface has no broker client code. Delivery to external systems is the
 * responsibility of the WO-005 outbox poller.
 */
public interface DomainEventPublisher {

    /**
     * Appends {@code event} to the outbox within the caller's active transaction.
     *
     * @param event the domain event to publish; payload must comply with PII rules
     * @throws IllegalTransactionStateException if called outside an active transaction
     * @throws com.fieldservice.platform.api.exception.PayloadTooLargeException if the
     *         serialised payload exceeds the configured maximum
     * @throws com.fieldservice.platform.outbox.RestrictedFieldException if the payload
     *         contains a non-null {@code @Restricted} field
     */
    void publish(DomainEvent event);
}

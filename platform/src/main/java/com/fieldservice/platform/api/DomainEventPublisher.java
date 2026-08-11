package com.fieldservice.platform.api;

/**
 * Platform port for publishing domain events into the transactional outbox.
 *
 * <p>The implementation uses {@link org.springframework.transaction.annotation.Propagation#MANDATORY}
 * propagation so that invoking this interface outside an active transaction throws immediately.
 * This guard makes it structurally impossible to accidentally create a non-atomic outbox row.
 *
 * <p>The outbox row is persisted in the same JDBC transaction as the domain state change and its
 * Envers revision. Commit yields exactly one event row; rollback yields none.
 *
 * <p>Downstream delivery (broker publishing) is handled by the WO-005 poller, which reads
 * unpublished rows using {@code FOR UPDATE SKIP LOCKED} and marks them {@code published_at}
 * after confirming broker delivery.
 */
public interface DomainEventPublisher {

    /**
     * Appends {@code event} as an outbox row in the caller's existing transaction.
     *
     * @param event the domain event to persist
     * @throws org.springframework.transaction.IllegalTransactionStateException if called
     *         outside an active transaction (propagation MANDATORY enforcement)
     * @throws com.fieldservice.platform.outbox.RestrictedDataInPayloadException if the payload
     *         map contains a value representing Restricted-classified data
     * @throws com.fieldservice.platform.outbox.PayloadTooLargeException if the serialised payload
     *         exceeds the configured maximum byte limit
     */
    void publish(DomainEvent event);
}

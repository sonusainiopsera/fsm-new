/**
 * Transactional outbox infrastructure for the Field Service platform (WO-004).
 *
 * <h2>Delivery semantics</h2>
 * <p>Events published via {@link com.fieldservice.platform.api.DomainEventPublisher} are stored
 * in the {@code outbox_event} table in the same JDBC transaction as the domain row mutation
 * and its Hibernate Envers revision. This guarantees:
 * <ul>
 *   <li>Commit → exactly one domain change + one Envers revision + one outbox row.</li>
 *   <li>Rollback → zero domain changes + zero Envers revisions + zero outbox rows.</li>
 * </ul>
 *
 * <h2>At-least-once delivery</h2>
 * <p>The WO-005 outbox poller delivers each row to the broker at least once. Consumers
 * <em>must</em> treat the {@code event_id} UUID as their deduplication key and implement
 * idempotent processing. Duplicate delivery is possible on poller restart or broker timeout.
 *
 * <h2>Consumer deduplication key</h2>
 * <p>The {@code event_id} is a UUIDv7 — monotonically increasing within a JVM process,
 * non-guessable, and safe to use as a primary key in consumer idempotency tables.
 *
 * <h2>Payload data classification</h2>
 * <p>All payloads are built from purpose-built payload records using
 * {@link com.fieldservice.platform.outbox.PiiRedactionUtility#toPayloadMap(Object)}.
 * Fields annotated {@link com.fieldservice.platform.outbox.annotation.Restricted} cause
 * an immediate exception; fields annotated
 * {@link com.fieldservice.platform.outbox.annotation.Confidential} are masked with
 * {@code "[REDACTED]"}.
 */
package com.fieldservice.platform.outbox;

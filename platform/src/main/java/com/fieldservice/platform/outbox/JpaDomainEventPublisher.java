package com.fieldservice.platform.outbox;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link DomainEventPublisher} that persists an outbox row in the caller's transaction.
 *
 * <p><b>Propagation MANDATORY</b>: invoking this bean outside an active transaction throws
 * {@link org.springframework.transaction.IllegalTransactionStateException} immediately — the guard
 * that makes non-atomic publishing structurally impossible.
 *
 * <p><b>Atomicity guarantee</b>: because the outbox row is written using the same
 * {@link EntityManager} (and therefore the same JDBC connection / transaction) as the domain
 * row and its Envers revision, commit yields exactly one domain change + one audit revision +
 * one outbox row. Rollback yields none.
 *
 * <p><b>Metrics</b>: a Micrometer counter {@code domain_event_published_total} is incremented
 * for each successfully persisted event, tagged by {@code event_type}.
 */
@Component
public class JpaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JpaDomainEventPublisher.class);

    private static final String COUNTER_NAME = "domain_event_published_total";

    @PersistenceContext
    private EntityManager entityManager;

    private final MeterRegistry meterRegistry;
    private final int maxPayloadBytes;

    public JpaDomainEventPublisher(
            MeterRegistry meterRegistry,
            @Value("${app.outbox.max-payload-bytes:65536}") int maxPayloadBytes) {
        this.meterRegistry = meterRegistry;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /**
     * Persists {@code event} as an outbox row inside the caller's active transaction.
     *
     * <p>Propagation MANDATORY: throws if no transaction is active. Payload is serialised and
     * size-checked before persisting. Micrometer counter is incremented after persist.
     *
     * @param event the domain event to publish
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        String payloadJson = PayloadSerializer.serialize(event.payload(), maxPayloadBytes);

        OutboxEvent outboxEvent = OutboxEvent.of(
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                payloadJson,
                event.traceId(),
                event.actorUserId()
        );

        entityManager.persist(outboxEvent);

        log.info("outbox.published eventId={} eventType={} aggregateType={} aggregateId={} traceId={} actor={}",
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.traceId(),
                event.actorUserId());

        meterRegistry.counter(COUNTER_NAME, "event_type", event.eventType()).increment();
    }
}

package com.fieldservice.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.PayloadTooLargeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link DomainEventPublisher} that appends an outbox row within the
 * caller's existing transaction.
 *
 * <p>Propagation {@code MANDATORY} ensures this can never silently open its own
 * transaction — calling outside a transaction throws {@code IllegalTransactionStateException}
 * immediately, preventing non-atomic event publishing.
 *
 * <p>Serialisation pipeline (per publish call):
 * <ol>
 *   <li>Run PII redaction: reject {@code @Restricted} fields, mask {@code @Confidential}.</li>
 *   <li>Serialise payload to JSON via a dedicated, strictly-configured ObjectMapper.</li>
 *   <li>Enforce payload size bound; throw {@link PayloadTooLargeException} if exceeded.</li>
 *   <li>Persist {@link OutboxEvent} via EntityManager (same transaction as caller).</li>
 *   <li>Increment Micrometer counter tagged by event type.</li>
 *   <li>Emit structured log line with traceId, actor, aggregate type/id.</li>
 * </ol>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class JpaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JpaDomainEventPublisher.class);

    private static final ObjectMapper PAYLOAD_MAPPER = buildPayloadMapper();

    private final EntityManager em;
    private final MeterRegistry meterRegistry;
    private final OutboxProperties properties;

    public JpaDomainEventPublisher(EntityManager em,
                                   MeterRegistry meterRegistry,
                                   OutboxProperties properties) {
        this.em            = em;
        this.meterRegistry = meterRegistry;
        this.properties    = properties;
    }

    @Override
    public void publish(DomainEvent event) {
        PiiRedaction.sanitize(event.payload());

        String json = serializePayload(event.payload());
        int byteLen = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (byteLen > properties.maxPayloadBytes()) {
            throw new PayloadTooLargeException(byteLen, properties.maxPayloadBytes());
        }

        OutboxEvent outboxEvent = OutboxEvent.from(event, json);
        em.persist(outboxEvent);

        counter(event.eventType()).increment();

        log.info("outbox_published event_id={} event_type={} aggregate_type={} aggregate_id={} trace_id={} actor={}",
                event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId(),
                event.traceId(), event.actorUserId());
    }

    private String serializePayload(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return PAYLOAD_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload serialisation failed: " + e.getMessage(), e);
        }
    }

    private Counter counter(String eventType) {
        return Counter.builder("outbox.published")
                .tag("eventType", eventType)
                .description("Number of domain events published to the outbox")
                .register(meterRegistry);
    }

    private static ObjectMapper buildPayloadMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }
}

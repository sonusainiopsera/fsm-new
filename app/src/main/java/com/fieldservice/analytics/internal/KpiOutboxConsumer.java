package com.fieldservice.analytics.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consumes outbox events for analytics, enforces idempotency, and enqueues
 * affected metric keys into the debounce registry.
 *
 * <p>This class is NOT itself an {@link EventHandler}; the {@link AnalyticsConfiguration}
 * registers one {@link EventHandler} bean per supported event type, each delegating here.
 * This keeps the routing map out of the platform's handler registry while allowing
 * the consumer to handle any number of event types through a single code path.
 *
 * <p>Runs inside the outbox poller's existing transaction
 * ({@code Propagation.MANDATORY}). The {@code processed_event} insert and the
 * in-memory debounce enqueue are therefore co-ordinated with the event's
 * {@code published_at} timestamp.
 */
@Component
class KpiOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(KpiOutboxConsumer.class);

    private final ProcessedEventRepository processedEventRepository;
    private final MetricDebounceRegistry   debounceRegistry;
    private final Clock                    clock;

    KpiOutboxConsumer(ProcessedEventRepository processedEventRepository,
                      MetricDebounceRegistry   debounceRegistry,
                      Clock                    clock) {
        this.processedEventRepository = processedEventRepository;
        this.debounceRegistry         = debounceRegistry;
        this.clock                    = clock;
    }

    /**
     * Processes one domain event. Called within the outbox poller's transaction.
     *
     * @throws Exception propagated to the poller for retry-backoff handling
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void accept(DomainEvent event) throws Exception {
        Set<String> metricKeys = MetricEventMapper.metricKeysFor(event.eventType());
        if (metricKeys.isEmpty()) {
            return;
        }

        String metricKeysCsv = String.join(",", metricKeys);

        int inserted = processedEventRepository.insertIfAbsent(
                event.eventId(), metricKeysCsv, clock.instant());

        if (inserted == 0) {
            log.debug("analytics_event_already_processed event_id={} event_type={}",
                    event.eventId(), event.eventType());
            return;
        }

        for (String metricKey : metricKeys) {
            debounceRegistry.enqueue(metricKey);
        }

        log.debug("analytics_event_consumed event_id={} event_type={} metrics_enqueued={}",
                event.eventId(), event.eventType(), metricKeys.size());
    }

    /**
     * Factory: creates a thin {@link EventHandler} adapter that routes one event type
     * to this consumer. Called by {@link AnalyticsConfiguration} for each supported type.
     */
    static EventHandler handlerFor(String eventType, KpiOutboxConsumer consumer) {
        return new EventHandler() {
            @Override
            public String supportedEventType() { return eventType; }

            @Override
            public void handle(DomainEvent event) throws Exception {
                consumer.accept(event);
            }
        };
    }
}

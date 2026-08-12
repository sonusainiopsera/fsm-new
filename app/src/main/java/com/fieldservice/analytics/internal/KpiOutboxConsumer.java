package com.fieldservice.analytics.internal;

import com.fieldservice.outbox.payload.PartsConsumedPayload;
import com.fieldservice.outbox.payload.SlaBreachedPayload;
import com.fieldservice.outbox.payload.SlaPolicyChangedPayload;
import com.fieldservice.outbox.payload.WorkOrderCreatedPayload;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outbox event consumer for the analytics read-model substrate (WO-161).
 *
 * <p>Maps domain event types to the metric keys they affect, then enqueues those keys
 * into the {@link MetricDebounceRegistry} for coalesced recomputation. The consumer
 * does NOT recompute projections directly — the debounce registry ensures at most one
 * recomputation per 15-second window regardless of event burst size.
 *
 * <p>Idempotency: uses the {@code analytics_processed_event} table (separate from the
 * platform {@code processed_event} table to allow independent retention management).
 * A duplicate event_id results in an INSERT conflict which is caught and the handler
 * returns without side effects.
 *
 * <p>Subscribes to: WorkOrderCreated, WorkOrderStateChanged, PartsConsumed.
 * A single consumer instance handles all supported types; the outbox drain routes
 * by event_type. To support multiple event types, three separate @Component handlers
 * delegate to this shared implementation.
 */
@Component
class KpiOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(KpiOutboxConsumer.class);

    static final String CONSUMER_NAME = "analytics.kpi";

    private static final Map<String, List<String>> EVENT_TO_METRICS = Map.of(
            WorkOrderCreatedPayload.EVENT_TYPE, List.of(
                    KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT,
                    BacklogMetricKeys.BACKLOG_OPEN_COUNT),
            WorkOrderStateChangedPayload.EVENT_TYPE, List.of(
                    KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT,
                    KpiAggregationQueries.METRIC_WO_COMPLETION_RATE_7D,
                    KpiAggregationQueries.METRIC_WO_SLA_COMPLIANCE_7D,
                    BacklogMetricKeys.BACKLOG_OPEN_COUNT,
                    BacklogMetricKeys.BACKLOG_ON_HOLD_COUNT,
                    BacklogMetricKeys.WORKLOAD_BALANCE_CV,
                    // WO-162: SLA metrics — a state change to COMPLETED/CLOSED affects compliance
                    SlaMetricKeys.COMPLIANCE_RATE,
                    SlaMetricKeys.RESOLUTION_MEAN,
                    SlaMetricKeys.RESOLUTION_MEDIAN,
                    SlaMetricKeys.BREACH_COUNT),
            PartsConsumedPayload.EVENT_TYPE, List.of(
                    KpiAggregationQueries.METRIC_WO_COMPLETION_RATE_7D),
            // WO-162: SLA breach detection affects breach count and compliance
            SlaBreachedPayload.EVENT_TYPE, List.of(
                    SlaMetricKeys.COMPLIANCE_RATE,
                    SlaMetricKeys.BREACH_COUNT),
            // WO-162: SLA policy change can alter which work orders are compliant
            SlaPolicyChangedPayload.EVENT_TYPE, List.of(
                    SlaMetricKeys.COMPLIANCE_RATE,
                    SlaMetricKeys.RESOLUTION_MEAN,
                    SlaMetricKeys.RESOLUTION_MEDIAN,
                    SlaMetricKeys.BREACH_COUNT)
    );

    private final MetricDebounceRegistry debounceRegistry;
    private final JdbcTemplate jdbcTemplate;

    KpiOutboxConsumer(MetricDebounceRegistry debounceRegistry, JdbcTemplate jdbcTemplate) {
        this.debounceRegistry = debounceRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Processes an outbox event: inserts idempotency record, maps to metric keys,
     * marks each dirty in the debounce registry.
     *
     * @return the metric keys that were enqueued (empty if already processed)
     */
    List<String> consume(EventHandlerContext ctx) {
        List<String> metricKeys = EVENT_TO_METRICS.getOrDefault(ctx.eventType(), List.of());
        if (metricKeys.isEmpty()) {
            log.debug("analytics.consumer.no_metrics: eventType={}", ctx.eventType());
            return List.of();
        }

        // Idempotency guard: insert into analytics_processed_event
        if (!claimEvent(ctx)) {
            log.debug("analytics.consumer.duplicate: eventId={} eventType={}",
                    ctx.eventId(), ctx.eventType());
            return List.of();
        }

        String touchedKeys = String.join(",", metricKeys);
        log.debug("analytics.consumer.consumed: eventId={} eventType={} metricKeys={}",
                ctx.eventId(), ctx.eventType(), touchedKeys);

        for (String metricKey : metricKeys) {
            debounceRegistry.markDirty(metricKey);
        }
        return metricKeys;
    }

    private boolean claimEvent(EventHandlerContext ctx) {
        try {
            String touchedKeys = String.join(",",
                    EVENT_TO_METRICS.getOrDefault(ctx.eventType(), List.of()));
            jdbcTemplate.update(
                    "INSERT INTO analytics_processed_event (event_id, metric_keys) VALUES (?, ?)",
                    ctx.eventId(), touchedKeys);
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    /** @return supported event types, for registration with the outbox drain service. */
    static Set<String> supportedEventTypes() {
        return EVENT_TO_METRICS.keySet();
    }
}

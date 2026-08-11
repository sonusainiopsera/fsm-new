package com.fieldservice.analytics.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandlerContext;
import com.fieldservice.analytics.internal.quality.RepeatVisitLinker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Outbox event consumer for first-time fix quality tracking (WO-164).
 *
 * <p>Called by {@link KpiEventHandlers.WorkOrderStateChangedHandler} (not registered as a
 * separate EventHandler, to avoid duplicate-key in OutboxDrainService). On each closure
 * transition it invokes {@link RepeatVisitLinker} and marks quality metric keys dirty.
 *
 * <p>Idempotency uses a consumer-prefixed UUID derived from the event ID so this consumer
 * and the substrate {@link KpiOutboxConsumer} track the same events independently.
 */
@Component
class QualityKpiOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(QualityKpiOutboxConsumer.class);

    static final String CONSUMER_PREFIX = "quality.kpi:";

    private static final Set<String> CLOSURE_STATES = Set.of("CLOSED", "COMPLETED");

    private final RepeatVisitLinker linker;
    private final MetricDebounceRegistry debounceRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    QualityKpiOutboxConsumer(
            RepeatVisitLinker linker,
            MetricDebounceRegistry debounceRegistry,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.linker = linker;
        this.debounceRegistry = debounceRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void consume(EventHandlerContext ctx) {
        if (!WorkOrderStateChangedPayload.EVENT_TYPE.equals(ctx.eventType())) return;

        if (!claimEvent(ctx)) {
            log.debug("quality.consumer.duplicate: eventId={}", ctx.eventId());
            return;
        }

        try {
            WorkOrderStateChangedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), WorkOrderStateChangedPayload.class);

            if (!CLOSURE_STATES.contains(payload.toState())) return;

            Instant closedAt = payload.transitionedAt() != null
                    ? payload.transitionedAt()
                    : Instant.now();

            linker.processClosureEvent(payload.workOrderId(), closedAt);

            QualityMetricKeys.ALL.forEach(debounceRegistry::markDirty);

            log.debug("quality.consumer.processed: eventId={} workOrderId={} state={}",
                    ctx.eventId(), payload.workOrderId(), payload.toState());

        } catch (Exception ex) {
            log.error("quality.consumer.error: eventId={} — {}", ctx.eventId(), ex.getMessage(), ex);
            throw new RuntimeException("Quality consumer failed for eventId=" + ctx.eventId(), ex);
        }
    }

    private boolean claimEvent(EventHandlerContext ctx) {
        UUID dedupeKey = UUID.nameUUIDFromBytes(
                (CONSUMER_PREFIX + ctx.eventId()).getBytes(StandardCharsets.UTF_8));
        try {
            jdbcTemplate.update(
                    "INSERT INTO analytics_processed_event (event_id, metric_keys) VALUES (?, ?)",
                    dedupeKey,
                    QualityMetricKeys.FTF_MATURED + "," + QualityMetricKeys.FTF_PROVISIONAL);
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}

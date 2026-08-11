package com.fieldservice.analytics.internal;

import com.fieldservice.outbox.payload.CsatResponseRecordedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Outbox event consumer for CSAT analytics (WO-173).
 *
 * <p>Handles {@code CsatResponseRecorded} events and marks the rolling-90-day
 * mean score and response rate metrics dirty in the {@link MetricDebounceRegistry}.
 *
 * <p>Idempotency is enforced via a consumer-prefixed deduplication key in the
 * {@code analytics_processed_event} table. A duplicate event_id is silently
 * ignored (DataIntegrityViolationException → return).
 */
@Component
class CsatKpiConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(CsatKpiConsumer.class);

    static final String CONSUMER_PREFIX = "csat.kpi:";

    private final MetricDebounceRegistry debounceRegistry;
    private final JdbcTemplate jdbcTemplate;

    CsatKpiConsumer(MetricDebounceRegistry debounceRegistry, JdbcTemplate jdbcTemplate) {
        this.debounceRegistry = debounceRegistry;
        this.jdbcTemplate     = jdbcTemplate;
    }

    @Override
    public String getSupportedEventType() {
        return CsatResponseRecordedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) {
        if (!claimEvent(ctx)) {
            log.debug("csat.kpi.duplicate: eventId={}", ctx.eventId());
            return;
        }

        CsatMetricKeys.ALL.forEach(debounceRegistry::markDirty);

        log.debug("csat.kpi.consumed: eventId={} metricKeys={}", ctx.eventId(), CsatMetricKeys.ALL);
    }

    private boolean claimEvent(EventHandlerContext ctx) {
        UUID dedupeKey = UUID.nameUUIDFromBytes(
                (CONSUMER_PREFIX + ctx.eventId()).getBytes(StandardCharsets.UTF_8));
        try {
            jdbcTemplate.update(
                    "INSERT INTO analytics_processed_event (event_id, metric_keys) VALUES (?, ?)",
                    dedupeKey,
                    String.join(",", CsatMetricKeys.ALL));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}

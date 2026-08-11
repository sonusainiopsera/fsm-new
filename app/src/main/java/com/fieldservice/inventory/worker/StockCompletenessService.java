package com.fieldservice.inventory.worker;

import com.fieldservice.inventory.ledger.StockLedgerRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes and publishes the parts-logging-completeness metric (O6 objective).
 *
 * <p>Completeness = (work orders closed in trailing 24 h window with at least one
 * CONSUMPTION record OR explicitly marked no_parts_required) / (all work orders
 * closed in trailing 24 h window).
 *
 * <p>A work order with no parts required ({@code no_parts_required = true}) is
 * treated as satisfied so the metric is not gamed by absence of data.
 */
@Service
public class StockCompletenessService {

    private static final Logger log = LoggerFactory.getLogger(StockCompletenessService.class);
    private static final Duration COMPLETENESS_WINDOW = Duration.ofHours(24);

    private final StockLedgerRepository ledgerRepository;
    private final JdbcTemplate          jdbcTemplate;
    private final Clock                 clock;

    /** Thread-safe holder for the gauge value. Updated by the worker sweep. */
    private final AtomicReference<Double> completenessRatio = new AtomicReference<>(1.0);

    public StockCompletenessService(StockLedgerRepository ledgerRepository,
                                     JdbcTemplate jdbcTemplate,
                                     Clock clock,
                                     MeterRegistry meterRegistry) {
        this.ledgerRepository = ledgerRepository;
        this.jdbcTemplate     = jdbcTemplate;
        this.clock            = clock;

        Gauge.builder("inventory_parts_logging_completeness_ratio", completenessRatio, AtomicReference::get)
                .description("Fraction of closed WOs with parts consumption or no_parts_required in trailing 24h")
                .register(meterRegistry);
    }

    /** Called by the reconciliation worker on each tick. */
    @Transactional(readOnly = true)
    public void updateCompletenessMetric() {
        Instant windowStart = clock.instant().minus(COMPLETENESS_WINDOW);

        // Work orders closed in the trailing window
        List<UUID> closedIds = jdbcTemplate.query(
                "SELECT id FROM work_order WHERE state = 'CLOSED' AND created_at >= ?",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                java.sql.Timestamp.from(windowStart));

        if (closedIds.isEmpty()) {
            completenessRatio.set(1.0);
            return;
        }

        // Work orders already marked as no_parts_required
        Set<UUID> noPartsRequired = jdbcTemplate.query(
                "SELECT id FROM work_order WHERE id = ANY(?) AND no_parts_required = TRUE",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                new Object[]{closedIds.stream()
                        .map(UUID::toString)
                        .toArray(String[]::new)})
                .stream().collect(java.util.stream.Collectors.toSet());

        // Work orders with at least one CONSUMPTION ledger entry
        List<UUID> withConsumption = ledgerRepository.workOrderIdsWithMovementsSince(windowStart);
        Set<UUID> withConsumptionSet = new java.util.HashSet<>(withConsumption);

        long satisfied = closedIds.stream()
                .filter(id -> noPartsRequired.contains(id) || withConsumptionSet.contains(id))
                .count();

        double ratio = (double) satisfied / closedIds.size();
        completenessRatio.set(ratio);

        log.info("parts_logging_completeness ratio={} satisfied={} total={}",
                String.format("%.3f", ratio), satisfied, closedIds.size());

        if (ratio < 0.95) {
            log.warn("ALERT parts_logging_completeness_below_threshold ratio={}", ratio);
        }
    }

    /** Returns the current completeness ratio (for testing). */
    public double getCurrentRatio() {
        return completenessRatio.get();
    }
}

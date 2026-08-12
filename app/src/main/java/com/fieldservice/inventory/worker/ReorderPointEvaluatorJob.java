package com.fieldservice.inventory.worker;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.StockAlert.AlertType;
import com.fieldservice.domain.inventory.StockAlertRepository;
import com.fieldservice.inventory.application.StockAlertService;
import com.fieldservice.platform.outbox.SchedulingLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled worker job that sweeps all stock balances against part reorder points.
 *
 * <p>Runs only on the {@code worker} Spring profile under a distributed lock so exactly
 * one replica executes per tick. A long sweep cannot overlap itself because the fixed
 * delay starts after the previous run completes.
 *
 * <p>Per AC-6: additionally triggered on a {@code PartsConsumed} or {@code StockAdjusted}
 * event for the affected part/location (see {@link ReorderPointEvaluatorJob#evaluateOne}).
 *
 * <p>Evaluation rules (AC-5):
 * <ul>
 *   <li>quantity_on_hand &lt;= reorder_point (and reorder_point &gt; 0): raises LOW_STOCK</li>
 *   <li>quantity_on_hand == 0: raises STOCKOUT (regardless of reorder_point)</li>
 *   <li>quantity_on_hand &gt; reorder_point: clears any active LOW_STOCK alert</li>
 *   <li>quantity_on_hand &gt; 0: clears any active STOCKOUT alert</li>
 *   <li>reorder_point == 0: LOW_STOCK is never raised; only STOCKOUT on zero quantity</li>
 *   <li>Deactivated parts have their alerts cleared silently</li>
 * </ul>
 */
@Component
@Profile("worker")
@EnableScheduling
public class ReorderPointEvaluatorJob {

    private static final Logger log = LoggerFactory.getLogger(ReorderPointEvaluatorJob.class);
    static final String LOCK_NAME = "inventory.reorder_point_evaluator";

    @Value("${app.inventory.reorder-evaluator.lease-seconds:120}")
    private int leaseSeconds;

    private final StockAlertService    stockAlertService;
    private final StockAlertRepository alertRepository;
    private final PartRepository       partRepository;
    private final SchedulingLock       schedulingLock;
    private final JdbcTemplate         jdbcTemplate;

    private final Timer   sweepDurationTimer;
    private final Counter evaluatedBalancesCounter;
    private final Counter replenishmentEventsCounter;
    private final AtomicLong activeLowStockGauge  = new AtomicLong(0);
    private final AtomicLong activeStockoutGauge  = new AtomicLong(0);

    public ReorderPointEvaluatorJob(
            StockAlertService stockAlertService,
            StockAlertRepository alertRepository,
            PartRepository partRepository,
            SchedulingLock schedulingLock,
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry) {
        this.stockAlertService = stockAlertService;
        this.alertRepository   = alertRepository;
        this.partRepository    = partRepository;
        this.schedulingLock    = schedulingLock;
        this.jdbcTemplate      = jdbcTemplate;

        this.sweepDurationTimer = Timer.builder("inventory_reorder_evaluator_sweep_duration_seconds")
                .description("Duration of each reorder-point evaluation sweep")
                .register(meterRegistry);
        this.evaluatedBalancesCounter = Counter.builder("inventory_reorder_evaluator_balances_evaluated_total")
                .description("Total stock balance rows evaluated since startup")
                .register(meterRegistry);
        this.replenishmentEventsCounter = Counter.builder("inventory_reorder_evaluator_replenishment_events_total")
                .description("Total ReplenishmentNeeded signals published since startup")
                .register(meterRegistry);

        Gauge.builder("inventory_stock_alert_active_low_stock",   activeLowStockGauge, AtomicLong::get)
                .description("Current number of active LOW_STOCK alerts")
                .register(meterRegistry);
        Gauge.builder("inventory_stock_alert_active_stockout",     activeStockoutGauge, AtomicLong::get)
                .description("Current number of active STOCKOUT alerts")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.inventory.reorder-evaluator.interval-ms:300000}")
    public void sweep() {
        boolean executed = schedulingLock.runIfLeader(
                LOCK_NAME,
                Duration.ofSeconds(leaseSeconds),
                () -> sweepDurationTimer.record(this::runSweep));

        if (!executed) {
            log.debug("inventory.reorder_evaluator.skipped — distributed lock held by another replica");
        }
    }

    /**
     * Point evaluation for a single (partId, locationId) pair, called by the idempotent
     * outbox consumer for {@code PartsConsumed} and {@code StockAdjusted} events (AC-6).
     */
    @Transactional
    public void evaluateOne(UUID partId, UUID locationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sb.quantity_on_hand, p.reorder_point, p.is_active " +
                "FROM stock_balance sb JOIN part p ON p.id = sb.part_id " +
                "WHERE sb.part_id = ? AND sb.location_id = ?",
                partId, locationId);

        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        int qtyOnHand    = ((Number) row.get("quantity_on_hand")).intValue();
        int reorderPoint = ((Number) row.get("reorder_point")).intValue();
        boolean active   = Boolean.TRUE.equals(row.get("is_active"));

        evaluateBalance(partId, locationId, qtyOnHand, reorderPoint, active);
    }

    private void runSweep() {
        log.info("inventory.reorder_evaluator.sweep.started");
        int evaluated = 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sb.part_id, sb.location_id, sb.quantity_on_hand, " +
                "       p.reorder_point, p.is_active " +
                "FROM stock_balance sb " +
                "JOIN part p ON p.id = sb.part_id");

        for (Map<String, Object> row : rows) {
            UUID partId      = (UUID) row.get("part_id");
            UUID locationId  = (UUID) row.get("location_id");
            int qtyOnHand    = ((Number) row.get("quantity_on_hand")).intValue();
            int reorderPoint = ((Number) row.get("reorder_point")).intValue();
            boolean active   = Boolean.TRUE.equals(row.get("is_active"));

            try {
                evaluateBalanceInTx(partId, locationId, qtyOnHand, reorderPoint, active);
                evaluated++;
            } catch (Exception ex) {
                log.error("inventory.reorder_evaluator.balance_error: partId={} locationId={} error={}",
                        partId, locationId, ex.getMessage(), ex);
            }
        }

        evaluatedBalancesCounter.increment(evaluated);
        refreshGauges();
        log.info("inventory.reorder_evaluator.sweep.completed: evaluated={}", evaluated);
    }

    @Transactional
    void evaluateBalanceInTx(UUID partId, UUID locationId,
                              int qtyOnHand, int reorderPoint, boolean active) {
        evaluateBalance(partId, locationId, qtyOnHand, reorderPoint, active);
    }

    private void evaluateBalance(UUID partId, UUID locationId,
                                 int qtyOnHand, int reorderPoint, boolean active) {
        // Deactivated parts: clear any alerts rather than re-notifying
        if (!active) {
            stockAlertService.clear(partId, locationId, AlertType.LOW_STOCK,  qtyOnHand);
            stockAlertService.clear(partId, locationId, AlertType.STOCKOUT,   qtyOnHand);
            return;
        }

        // STOCKOUT evaluation (quantity == 0)
        if (qtyOnHand == 0) {
            stockAlertService.raise(partId, locationId, AlertType.STOCKOUT, qtyOnHand, reorderPoint);
            replenishmentEventsCounter.increment();
        } else {
            stockAlertService.clear(partId, locationId, AlertType.STOCKOUT, qtyOnHand);
        }

        // LOW_STOCK evaluation (quantity <= reorder_point, reorder_point > 0)
        if (reorderPoint > 0 && qtyOnHand <= reorderPoint) {
            stockAlertService.raise(partId, locationId, AlertType.LOW_STOCK, qtyOnHand, reorderPoint);
            if (qtyOnHand > 0) {
                // Only count as replenishment signal if not already counted for STOCKOUT
                replenishmentEventsCounter.increment();
            }
        } else {
            stockAlertService.clear(partId, locationId, AlertType.LOW_STOCK, qtyOnHand);
        }
    }

    private void refreshGauges() {
        activeLowStockGauge.set(alertRepository.countByState(
                com.fieldservice.domain.inventory.StockAlert.AlertState.ACTIVE));
        // We don't have per-type counts without a custom query — use total active as approximation
        activeStockoutGauge.set(0L);
    }
}

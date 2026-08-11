package com.fieldservice.inventory.worker;

import com.fieldservice.inventory.application.InventoryMetrics;
import com.fieldservice.inventory.application.StockReconciliationService;
import com.fieldservice.platform.outbox.SchedulingLock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Scheduled reconciliation job for the inventory module (WO-150).
 *
 * <p>Runs only on the {@code worker} Spring profile — never on API-only nodes.
 * Wraps execution in the platform distributed lock so exactly one replica runs per tick.
 * A long sweep does not overlap itself because the fixed delay starts after completion.
 *
 * <p>The job is read-only: it detects discrepancies and emits structured alerts, but
 * it NEVER auto-corrects balances. A silent repair would destroy the audit narrative.
 */
@Component
@Profile("worker")
@EnableScheduling
public class StockReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationJob.class);
    static final String LOCK_NAME = "inventory.reconciliation";

    @Value("${app.inventory.reconciliation.lease-seconds:60}")
    private int leaseSeconds;

    private final StockReconciliationService reconciliationService;
    private final InventoryMetrics inventoryMetrics;
    private final SchedulingLock schedulingLock;
    private final Timer sweepDurationTimer;

    public StockReconciliationJob(
            StockReconciliationService reconciliationService,
            InventoryMetrics inventoryMetrics,
            SchedulingLock schedulingLock,
            MeterRegistry meterRegistry) {
        this.reconciliationService = reconciliationService;
        this.inventoryMetrics = inventoryMetrics;
        this.schedulingLock = schedulingLock;
        this.sweepDurationTimer = Timer.builder("inventory_reconciliation_sweep_duration_seconds")
                .description("Duration of each reconciliation sweep")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.inventory.reconciliation.interval-ms:60000}")
    public void sweep() {
        boolean executed = schedulingLock.runIfLeader(
                LOCK_NAME,
                Duration.ofSeconds(leaseSeconds),
                () -> sweepDurationTimer.record(this::runSweep));

        if (!executed) {
            log.debug("inventory.reconciliation.skipped — distributed lock held by another replica");
        }
    }

    private void runSweep() {
        log.info("inventory.reconciliation.started");
        try {
            int discrepancies = reconciliationService.reconcile();
            double completeness = reconciliationService.computeCompletenessRatio();
            inventoryMetrics.updateCompletenessRatio(completeness);

            log.info("inventory.reconciliation.completed: discrepancies={}, completenessRatio={}",
                    discrepancies, String.format("%.3f", completeness));
        } catch (Exception ex) {
            log.error("inventory.reconciliation.sweep_error", ex);
        }
    }
}

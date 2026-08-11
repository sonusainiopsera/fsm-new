package com.fieldservice.inventory.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer meters for the inventory module (WO-150).
 *
 * <p>All meters are in the {@code inventory} namespace and exposed via the Prometheus
 * actuator endpoint ({@code /actuator/prometheus}) bound to the internal network.
 */
@Component
public class InventoryMetrics {

    private final Counter reconciliationDiscrepanciesTotal;
    private final Counter negativeStockIncidentsTotal;
    private final Counter ledgerEntriesWrittenTotal;
    private final AtomicLong completenessRatioMillis = new AtomicLong(0);

    public InventoryMetrics(MeterRegistry registry) {
        this.reconciliationDiscrepanciesTotal = Counter.builder("inventory_reconciliation_discrepancies_total")
                .description("Number of (part, location) pairs where ledger sum != quantity_on_hand")
                .register(registry);

        this.negativeStockIncidentsTotal = Counter.builder("inventory_negative_stock_incidents_total")
                .description("Number of times a negative stock balance was detected")
                .register(registry);

        this.ledgerEntriesWrittenTotal = Counter.builder("inventory_ledger_entries_written_total")
                .description("Total stock_ledger rows written since startup")
                .register(registry);

        // Gauge for completeness ratio (stored as millipercent for atomic long arithmetic)
        registry.gauge("inventory_parts_logging_completeness_ratio",
                completenessRatioMillis, v -> v.get() / 1000.0);
    }

    public void incrementReconciliationDiscrepancy() {
        reconciliationDiscrepanciesTotal.increment();
    }

    public void incrementNegativeStockIncident() {
        negativeStockIncidentsTotal.increment();
    }

    public void incrementLedgerEntriesWritten() {
        ledgerEntriesWrittenTotal.increment();
    }

    /** Updates the parts-logging completeness ratio (0.0–1.0). */
    public void updateCompletenessRatio(double ratio) {
        completenessRatioMillis.set((long) (ratio * 1000));
    }
}

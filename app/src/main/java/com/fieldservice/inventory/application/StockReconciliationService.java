package com.fieldservice.inventory.application;

import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockBalanceRepository;
import com.fieldservice.domain.inventory.StockLedgerRepository;
import com.fieldservice.domain.inventory.WorkOrderPartRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconciliation service: sums stock_ledger deltas per (part, location) and asserts
 * equality with quantity_on_hand in stock_balance.
 *
 * <p>Read-only against stock tables — alerts on discrepancy, never auto-corrects.
 * A single failing pair does not abort the sweep; errors are counted per pair.
 */
@Service
@Transactional(readOnly = true)
public class StockReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationService.class);

    /** Trailing window for the parts-logging-completeness metric. */
    static final Duration COMPLETENESS_WINDOW = Duration.ofHours(24);

    private final StockLedgerRepository ledgerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final InventoryMetrics metrics;
    private final Clock clock;

    public StockReconciliationService(
            StockLedgerRepository ledgerRepository,
            JdbcTemplate jdbcTemplate,
            InventoryMetrics metrics,
            Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Runs the full reconciliation sweep: sums ledger deltas and compares to balances.
     *
     * @return number of discrepancies found
     */
    public int reconcile() {
        int discrepancyCount = 0;

        // Sum deltas per (part, location) from ledger
        List<StockLedgerRepository.LedgerSum> ledgerSums = ledgerRepository.sumDeltaByPartAndLocation();

        // Build a map of (part_id:location_id) -> balance from stock_balance
        Map<String, Long> balanceMap = fetchBalanceMap();

        for (StockLedgerRepository.LedgerSum ledgerSum : ledgerSums) {
            try {
                String key = ledgerSum.getPartId() + ":" + ledgerSum.getLocationId();
                long balanceQty = balanceMap.getOrDefault(key, 0L);
                long ledgerTotal = ledgerSum.getTotalDelta();

                if (balanceQty != ledgerTotal) {
                    discrepancyCount++;
                    metrics.incrementReconciliationDiscrepancy();
                    if (balanceQty < 0) {
                        metrics.incrementNegativeStockIncident();
                    }
                    log.error(
                        "inventory.reconciliation.discrepancy: partId={}, locationId={}, " +
                        "ledgerSum={}, balanceQty={} — ALERT: balance and ledger diverged; " +
                        "inspect stock_ledger tail for this pair. " +
                        "Do NOT manually edit stock_balance; use an ADJUSTMENT ledger entry.",
                        ledgerSum.getPartId(), ledgerSum.getLocationId(), ledgerTotal, balanceQty);
                }
            } catch (Exception ex) {
                discrepancyCount++;
                log.error("inventory.reconciliation.pair_error: partId={}, locationId={}",
                        ledgerSum.getPartId(), ledgerSum.getLocationId(), ex);
            }
        }

        // Also check for parts+locations in stock_balance with no ledger history
        for (Map.Entry<String, Long> entry : balanceMap.entrySet()) {
            // A zero balance with no ledger history is valid — skip
            if (entry.getValue() == 0) continue;

            boolean hasLedgerEntry = ledgerSums.stream()
                    .anyMatch(s -> (s.getPartId() + ":" + s.getLocationId()).equals(entry.getKey()));

            if (!hasLedgerEntry) {
                discrepancyCount++;
                metrics.incrementReconciliationDiscrepancy();
                log.error(
                    "inventory.reconciliation.no_ledger: key={}, balanceQty={} — " +
                    "non-zero balance has no ledger history",
                    entry.getKey(), entry.getValue());
            }
        }

        return discrepancyCount;
    }

    /**
     * Computes the parts-logging completeness ratio for the trailing window.
     *
     * <p>Ratio = work_orders_with_parts_or_no_parts_required / work_orders_closed_in_window
     * A zero denominator (no closures in window) returns 1.0 (trivially complete).
     */
    public double computeCompletenessRatio() {
        Instant windowStart = clock.instant().minus(COMPLETENESS_WINDOW);

        // Work orders closed within the trailing window
        List<Map<String, Object>> closedWos = jdbcTemplate.queryForList(
                "SELECT id, no_parts_required, updated_at FROM work_order " +
                "WHERE state = 'COMPLETED' AND updated_at >= ?",
                java.sql.Timestamp.from(windowStart));

        if (closedWos.isEmpty()) {
            return 1.0;
        }

        int total = closedWos.size();
        int satisfied = 0;

        for (Map<String, Object> wo : closedWos) {
            UUID woId = (UUID) wo.get("id");
            boolean noPartsRequired = Boolean.TRUE.equals(wo.get("no_parts_required"));
            java.sql.Timestamp closedAt = (java.sql.Timestamp) wo.get("updated_at");
            Instant closedInstant = closedAt.toInstant();

            if (noPartsRequired) {
                satisfied++;
            } else {
                // Check if at least one ledger entry exists within 24h after closure
                long count = ledgerRepository.countEntriesForWorkOrderAfter(
                        woId, closedInstant.minus(COMPLETENESS_WINDOW));
                if (count > 0) {
                    satisfied++;
                }
            }
        }

        return (double) satisfied / total;
    }

    private Map<String, Long> fetchBalanceMap() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT part_id, location_id, quantity_on_hand FROM stock_balance");
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID partId = (UUID) row.get("part_id");
            UUID locationId = (UUID) row.get("location_id");
            Number qty = (Number) row.get("quantity_on_hand");
            result.put(partId + ":" + locationId, qty != null ? qty.longValue() : 0L);
        }
        return result;
    }
}

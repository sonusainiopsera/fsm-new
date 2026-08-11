package com.fieldservice.inventory.worker;

import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.ledger.LedgerSumProjection;
import com.fieldservice.inventory.ledger.StockLedgerRepository;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Reconciliation service that sums ledger deltas per (part, location) and compares
 * them with quantity_on_hand in stock_balance.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Read-only against stock tables — never writes to stock rows.</li>
 *   <li>Alerts on discrepancy but never auto-corrects (would destroy audit narrative).</li>
 *   <li>A single failing pair does not abort the sweep — errors are counted per pair.</li>
 *   <li>Runs against the read replica where configured.</li>
 * </ul>
 */
@Service
public class StockReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationService.class);

    private final StockLedgerRepository  ledgerRepository;
    private final StockBalanceRepository balanceRepository;
    private final Clock                  clock;
    private final Counter                discrepancyCounter;
    private final Counter                negativeStockCounter;
    private final Timer                  sweepDurationTimer;

    public StockReconciliationService(StockLedgerRepository ledgerRepository,
                                       StockBalanceRepository balanceRepository,
                                       Clock clock,
                                       MeterRegistry meterRegistry) {
        this.ledgerRepository  = ledgerRepository;
        this.balanceRepository = balanceRepository;
        this.clock             = clock;

        this.discrepancyCounter = Counter.builder("inventory_reconciliation_discrepancies_total")
                .description("Number of (part, location) pairs where ledger sum != balance")
                .register(meterRegistry);

        this.negativeStockCounter = Counter.builder("inventory_negative_stock_incidents_total")
                .description("Count of negative-stock incidents detected during reconciliation")
                .register(meterRegistry);

        this.sweepDurationTimer = Timer.builder("inventory_reconciliation_sweep_duration_seconds")
                .description("Duration of a full reconciliation sweep")
                .register(meterRegistry);
    }

    /**
     * Runs a full reconciliation sweep.
     *
     * @return number of discrepancies found
     */
    @Transactional(readOnly = true)
    public int runSweep() {
        Instant start = clock.instant();
        int[] counts = {0}; // discrepancy count (array for lambda capture)

        sweepDurationTimer.record(() -> {
            try {
                counts[0] = doSweep();
            } catch (Exception ex) {
                log.error("reconciliation_sweep_failed", ex);
            }
        });

        log.info("reconciliation_sweep_complete discrepancies={} duration_ms={}",
                counts[0],
                Duration.between(start, clock.instant()).toMillis());
        return counts[0];
    }

    private int doSweep() {
        // Load ledger sums per (part, location)
        List<LedgerSumProjection> ledgerSums = ledgerRepository.sumDeltasByPartAndLocation();

        // Load all balances into a map for O(1) lookup
        Map<String, Integer> balanceMap = new HashMap<>();
        balanceRepository.findAll().forEach(b ->
                balanceMap.put(key(b.getPartId(), b.getLocationId()), b.getQuantityOnHand()));

        int discrepancies = 0;

        for (LedgerSumProjection sum : ledgerSums) {
            try {
                discrepancies += evaluatePair(sum, balanceMap);
            } catch (Exception ex) {
                log.error("reconciliation_pair_failed part_id={} location_id={}",
                        sum.getPartId(), sum.getLocationId(), ex);
                discrepancyCounter.increment();
                discrepancies++;
            }
        }

        // Also check for negative balances (invariant breach)
        balanceRepository.findAll().forEach(b -> {
            if (b.getQuantityOnHand() < 0) {
                log.error("ALERT negative_stock_detected part_id={} location_id={} quantity={}",
                        b.getPartId(), b.getLocationId(), b.getQuantityOnHand());
                negativeStockCounter.increment();
            }
        });

        // A part+location with no ledger history and a zero balance is fine — no false positive
        return discrepancies;
    }

    private int evaluatePair(LedgerSumProjection sum, Map<String, Integer> balanceMap) {
        String k = key(sum.getPartId(), sum.getLocationId());
        Integer balanceQty = balanceMap.get(k);

        if (balanceQty == null) {
            // Ledger has entries for a pair with no balance row — alert
            log.error("ALERT reconciliation_discrepancy part_id={} location_id={} " +
                            "ledger_sum={} balance=MISSING",
                    sum.getPartId(), sum.getLocationId(), sum.getTotalDelta());
            discrepancyCounter.increment();
            return 1;
        }

        long ledgerSum = sum.getTotalDelta() != null ? sum.getTotalDelta() : 0L;
        if (ledgerSum != balanceQty) {
            log.error("ALERT reconciliation_discrepancy part_id={} location_id={} " +
                            "ledger_sum={} balance={} diff={}",
                    sum.getPartId(), sum.getLocationId(),
                    ledgerSum, balanceQty, (balanceQty - ledgerSum));
            discrepancyCounter.increment();
            return 1;
        }
        return 0;
    }

    private static String key(UUID partId, UUID locationId) {
        return partId + ":" + locationId;
    }
}

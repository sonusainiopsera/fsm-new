package com.fieldservice.inventory.worker;

import com.fieldservice.inventory.application.StockAlertService;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sweeps all stock balances and evaluates each against the part's reorder_point.
 *
 * <p>Runs inside a Transactional boundary per page to avoid holding a single long
 * transaction across the whole sweep. Each balance evaluation is committed or rolled
 * back independently — a failing row does not abort the tick.
 */
@Service
public class StockThresholdEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(StockThresholdEvaluatorService.class);
    private static final int PAGE_SIZE = 200;

    private final StockBalanceRepository stockBalanceRepository;
    private final StockAlertService      stockAlertService;
    private final Clock                  clock;

    private final Timer   sweepTimer;
    private final Counter evaluatedCounter;
    private final Counter errorCounter;
    private final Counter replenishmentPublishedCounter;
    private final AtomicLong activeLowStockGauge  = new AtomicLong(0);
    private final AtomicLong activeStockoutGauge  = new AtomicLong(0);

    public StockThresholdEvaluatorService(StockBalanceRepository stockBalanceRepository,
                                           StockAlertService stockAlertService,
                                           Clock clock,
                                           MeterRegistry meterRegistry) {
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockAlertService      = stockAlertService;
        this.clock                  = clock;

        this.sweepTimer = Timer.builder("inventory.threshold.sweep.duration")
                .description("Duration of a full stock threshold sweep")
                .register(meterRegistry);
        this.evaluatedCounter = Counter.builder("inventory.threshold.evaluated.total")
                .description("Total stock balances evaluated in threshold sweeps")
                .register(meterRegistry);
        this.errorCounter = Counter.builder("inventory.threshold.evaluation.errors.total")
                .description("Evaluation errors (rows that failed, logged and skipped)")
                .register(meterRegistry);
        this.replenishmentPublishedCounter = Counter.builder("inventory.replenishment.published.total")
                .description("ReplenishmentNeeded events published by threshold evaluation")
                .register(meterRegistry);

        Gauge.builder("inventory.alerts.low_stock.active", activeLowStockGauge, AtomicLong::get)
                .description("Count of active LOW_STOCK alerts")
                .register(meterRegistry);
        Gauge.builder("inventory.alerts.stockout.active", activeStockoutGauge, AtomicLong::get)
                .description("Count of active STOCKOUT alerts")
                .register(meterRegistry);
    }

    /** Runs a full sweep of all stock balances, paginated to avoid heap pressure. */
    public void runSweep() {
        Instant start = clock.instant();
        sweepTimer.record(() -> {
            long evaluated = 0L;
            int page = 0;
            Page<StockBalance> batch;
            do {
                batch = stockBalanceRepository.findAll(PageRequest.of(page, PAGE_SIZE));
                for (StockBalance balance : batch) {
                    evaluateSingle(balance);
                    evaluated++;
                }
                page++;
            } while (batch.hasNext());

            evaluatedCounter.increment(evaluated);
            log.info("stock_threshold_sweep_complete evaluated={} duration_ms={}",
                    evaluated, Duration.between(start, clock.instant()).toMillis());
        });
    }

    /**
     * Evaluates a single balance — entry point for event-triggered evaluation
     * (PartsConsumed / StockAdjusted) so only the affected row is re-checked.
     */
    @Transactional
    public void evaluateSingle(StockBalance balance) {
        try {
            int qty = balance.getQuantityOnHand() != null ? balance.getQuantityOnHand() : 0;
            stockAlertService.evaluate(balance.getPartId(), balance.getLocationId(), qty, null);
            replenishmentPublishedCounter.increment();
        } catch (Exception ex) {
            errorCounter.increment();
            log.warn("stock_threshold_eval_failed part_id={} location_id={} reason={}",
                    balance.getPartId(), balance.getLocationId(), ex.getMessage(), ex);
        }
    }
}

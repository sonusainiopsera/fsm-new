package com.fieldservice.inventory.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Scheduled worker that drives the stock threshold evaluation sweep.
 *
 * <p>Runs only on the {@code worker} Spring profile so it is absent from the API
 * deployable and cannot consume API request threads.
 *
 * <p>A distributed lock ({@code pg_try_advisory_lock}) ensures exactly one replica
 * executes per tick. Lock acquisition failure is a debug-level no-op.
 *
 * <p>Sweep interval is configurable via
 * {@code app.inventory.threshold.interval-ms} (default 5 min).
 */
@Component
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(
        prefix      = "app.inventory.threshold",
        name        = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StockThresholdEvaluatorWorker {

    private static final Logger log = LoggerFactory.getLogger(StockThresholdEvaluatorWorker.class);

    /** Stable advisory lock key — must not collide with other workers in the same DB. */
    private static final long ADVISORY_LOCK_KEY = 0x5354_4B54_4852_4553L; // "STKTHR"

    private final StockThresholdEvaluatorService evaluatorService;
    private final JdbcTemplate                   jdbcTemplate;
    private final Clock                          clock;

    public StockThresholdEvaluatorWorker(StockThresholdEvaluatorService evaluatorService,
                                          JdbcTemplate jdbcTemplate,
                                          Clock clock) {
        this.evaluatorService = evaluatorService;
        this.jdbcTemplate     = jdbcTemplate;
        this.clock            = clock;
    }

    @Scheduled(fixedDelayString = "${app.inventory.threshold.interval-ms:300000}")
    public void sweep() {
        if (!tryAcquireLock()) {
            log.debug("threshold_lock_skipped another_replica_is_sweeping=true");
            return;
        }
        try {
            Instant start = clock.instant();
            log.info("stock_threshold_sweep_started at={}", start);
            evaluatorService.runSweep();
        } finally {
            releaseLock();
        }
    }

    private boolean tryAcquireLock() {
        try {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException ex) {
            log.warn("threshold_advisory_lock_acquire_failed", ex);
            return false;
        }
    }

    private void releaseLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
        } catch (DataAccessException ex) {
            log.warn("threshold_advisory_lock_release_failed", ex);
        }
    }
}

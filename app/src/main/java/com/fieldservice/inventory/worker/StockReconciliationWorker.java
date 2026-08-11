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
import java.util.UUID;

/**
 * Scheduled reconciliation sweep for the stock ledger.
 *
 * <p>Runs only on the {@code worker} Spring profile so it is absent from the API
 * deployable and cannot consume API request threads.
 *
 * <p>A distributed lock (advisory lock via PostgreSQL {@code pg_try_advisory_lock})
 * ensures exactly one replica runs the sweep per tick. Lock acquisition failure is a
 * debug-level no-op — the other replica is already sweeping.
 *
 * <p>The sweep interval is configurable via
 * {@code app.inventory.reconciliation.interval-ms} (default 60 s).
 */
@Component
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(
        prefix  = "app.inventory.reconciliation",
        name    = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StockReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationWorker.class);

    /** Stable advisory lock key — must not collide with other workers in the same DB. */
    private static final long ADVISORY_LOCK_KEY = 0x534C474552_4C4F434BL; // "SLGERLK"

    private final StockReconciliationService reconciliationService;
    private final StockCompletenessService   completenessService;
    private final JdbcTemplate               jdbcTemplate;
    private final Clock                      clock;

    public StockReconciliationWorker(StockReconciliationService reconciliationService,
                                      StockCompletenessService completenessService,
                                      JdbcTemplate jdbcTemplate,
                                      Clock clock) {
        this.reconciliationService = reconciliationService;
        this.completenessService   = completenessService;
        this.jdbcTemplate          = jdbcTemplate;
        this.clock                 = clock;
    }

    @Scheduled(fixedDelayString = "${app.inventory.reconciliation.interval-ms:60000}")
    public void sweep() {
        if (!tryAcquireLock()) {
            log.debug("reconciliation_lock_skipped another_replica_is_sweeping=true");
            return;
        }
        try {
            Instant sweepStart = clock.instant();
            log.info("reconciliation_sweep_started at={}", sweepStart);
            reconciliationService.runSweep();
            completenessService.updateCompletenessMetric();
            log.debug("reconciliation_sweep_finished at={}", clock.instant());
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
            log.warn("advisory_lock_acquire_failed", ex);
            return false;
        }
    }

    private void releaseLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
        } catch (DataAccessException ex) {
            log.warn("advisory_lock_release_failed", ex);
        }
    }
}

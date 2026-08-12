package com.fieldservice.aiaudit.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Daily purge job that physically deletes {@code ai_interaction} rows past their
 * {@code retain_until} timestamp.
 *
 * <p>Design:
 * <ul>
 *   <li>PostgreSQL session-level advisory lock prevents concurrent execution across
 *       worker replicas.</li>
 *   <li>Deletion operates in bounded batches (configurable via {@code ai.audit.purge-job-batch-size})
 *       to avoid long-running transactions and excessive lock contention.</li>
 *   <li>Physical deletion (not soft-delete) per the platform data retention policy.</li>
 *   <li>Idempotent: repeated runs produce no side effects on already-deleted rows.</li>
 * </ul>
 *
 * <p>Retention periods are stored on each row at insert time — changing the configuration
 * does NOT retroactively alter existing {@code retain_until} values. A backfill decision
 * must be made explicitly by operations staff and documented.
 */
@Component
class AiInteractionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionPurgeJob.class);

    // Stable 64-bit key for the purge advisory lock — must never change after deployment.
    // Derived from "aipurge" as ASCII bytes.
    static final long LOCK_KEY = 0x6169707572676500L;

    private final AiInteractionRepository repository;
    private final AiAuditProperties       properties;
    private final JdbcTemplate            jdbc;

    AiInteractionPurgeJob(AiInteractionRepository repository,
                           AiAuditProperties properties,
                           JdbcTemplate jdbc) {
        this.repository = repository;
        this.properties = properties;
        this.jdbc       = jdbc;
    }

    /**
     * Runs once per day at 02:30 UTC. Configurable via {@code ai.audit.purge-cron}.
     */
    @Scheduled(cron = "${ai.audit.purge-cron:0 30 2 * * *}")
    public void purge() {
        if (!tryAcquireLock()) {
            log.debug("ai_interaction_purge_skipped reason=lock_not_acquired");
            return;
        }
        try {
            runPurge();
        } finally {
            releaseLock();
        }
    }

    @Transactional
    void runPurge() {
        Instant now       = Instant.now();
        int     batchSize = properties.purgeJobBatchSize();
        int     total     = 0;
        int     deleted;

        log.info("ai_interaction_purge_started batch_size={}", batchSize);

        do {
            deleted = repository.deleteExpiredBatch(now, batchSize);
            total  += deleted;
            if (deleted > 0) {
                log.debug("ai_interaction_purge_batch deleted={} cumulative={}", deleted, total);
            }
        } while (deleted == batchSize);

        log.info("ai_interaction_purge_completed total_deleted={}", total);
    }

    private boolean tryAcquireLock() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock() {
        try {
            jdbc.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (Exception ex) {
            log.warn("ai_interaction_purge_lock_release_failed: {}", ex.getMessage());
        }
    }
}

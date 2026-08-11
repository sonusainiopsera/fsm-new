package com.fieldservice.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Scheduled job that purges expired idempotency-key records in bounded batches.
 *
 * <p>Uses a PostgreSQL advisory lock to ensure only one instance runs at a time in a
 * multi-node deployment. This is the leader-election mechanism until WO-005
 * (SchedulingLock primitive) becomes available.
 *
 * <p>Runs on the {@code worker} profile so it does not execute on API-only nodes.
 */
@Component
@Profile("worker")
public class IdempotencyKeyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob.class);

    /** Stable advisory lock key for this job across all nodes. */
    private static final long ADVISORY_LOCK_KEY = 0x49444b50L; // "IDKP" as long

    @Value("${app.idempotency.purge-batch-size:500}")
    private int batchSize;

    private final IdempotencyKeyRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public IdempotencyKeyPurgeJob(IdempotencyKeyRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Purge expired completed/non-replayable records. Runs every 5 minutes. */
    @Scheduled(fixedDelayString = "${app.idempotency.purge-interval:300000}")
    @Transactional
    public void purgeExpired() {
        if (!tryAcquireLock()) {
            log.debug("Idempotency purge skipped — another node holds the advisory lock");
            return;
        }
        try {
            Instant now = Instant.now();
            int deleted = repository.deleteExpiredBatch(now, batchSize);
            if (deleted > 0) {
                log.info("Purged {} expired idempotency records", deleted);
            }
            int stale = repository.deleteStaleLeasedBatch(now, batchSize);
            if (stale > 0) {
                log.info("Purged {} stale IN_PROGRESS idempotency records", stale);
            }
        } finally {
            releaseLock();
        }
    }

    private boolean tryAcquireLock() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
        } catch (Exception e) {
            log.warn("Failed to release advisory lock: {}", e.getMessage());
        }
    }
}

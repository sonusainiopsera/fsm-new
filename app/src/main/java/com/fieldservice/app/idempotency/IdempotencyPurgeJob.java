package com.fieldservice.app.idempotency;

import com.fieldservice.platform.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled purge job that removes expired idempotency-key rows.
 *
 * <p>Runs every 15 minutes. Deletes rows in a single bounded batch via
 * {@link IdempotencyStore#purgeExpired()} — each call executes one DELETE in its own
 * transaction so a large backlog is cleaned incrementally without blocking live traffic.
 *
 * <p>In a multi-replica deployment both replicas will run this job independently.
 * Concurrent deletes are safe because PostgreSQL row-level locking prevents double-deletion.
 * A true distributed lock (WO-005) can be wired in once available; for now the
 * idempotent DELETE keeps this safe without coordination overhead.
 *
 * <p>Enabled only when {@code app.idempotency.purge-job-enabled=true} (default true).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.idempotency", name = "purge-job-enabled",
        havingValue = "true", matchIfMissing = true)
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyStore store;

    public IdempotencyPurgeJob(IdempotencyStore store) {
        this.store = store;
    }

    /** Runs every 15 minutes; deletes all rows whose {@code expires_at} is in the past. */
    @Scheduled(fixedDelayString = "${app.idempotency.purge-interval-ms:900000}")
    public void purge() {
        log.debug("idempotency_purge_start");
        int deleted = store.purgeExpired();
        if (deleted > 0) {
            log.info("idempotency_purge_complete deleted_rows={}", deleted);
        }
    }
}

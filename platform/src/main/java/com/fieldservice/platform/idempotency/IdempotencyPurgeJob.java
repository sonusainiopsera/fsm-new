package com.fieldservice.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker-profile purge job that deletes expired idempotency records in bounded batches.
 * Uses a PostgreSQL advisory lock so only one replica runs the purge at a time.
 */
@Component
@Profile("worker")
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    // Unique advisory lock key for idempotency purge — must not collide with other advisory locks
    private static final long ADVISORY_LOCK_KEY = 7349812543678234L;

    private final JdbcTemplate jdbc;
    private final IdempotencyKeyProperties properties;
    private final IdempotencyMetrics metrics;

    public IdempotencyPurgeJob(JdbcTemplate jdbc, IdempotencyKeyProperties properties,
                                IdempotencyMetrics metrics) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${fieldservice.idempotency.purge-interval:PT1H}")
    @Transactional
    public void purge() {
        // pg_try_advisory_xact_lock acquires a transaction-scoped advisory lock.
        // Returns true if acquired (we are the leader), false if already held.
        Boolean locked = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")",
                Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("Idempotency purge skipped — advisory lock held by another replica");
            return;
        }

        int deleted = jdbc.update(
                "DELETE FROM idempotency_key WHERE id IN " +
                "(SELECT id FROM idempotency_key WHERE expires_at < now() LIMIT ?)",
                properties.getPurgeBatchSize());

        if (deleted > 0) {
            metrics.addPurged(deleted);
            log.info("Purged {} expired idempotency records", deleted);
        } else {
            log.debug("Idempotency purge: no expired records found");
        }
    }
}

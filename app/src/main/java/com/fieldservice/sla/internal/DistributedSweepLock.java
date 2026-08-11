package com.fieldservice.sla.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Distributed advisory-lock guard for the SLA sweep.
 *
 * <p>Uses PostgreSQL's {@code pg_try_advisory_lock} keyed on a stable long constant
 * derived from the name "SLA_SWEEP". Advisory locks are connection-scoped: if the
 * worker process dies, the database connection is closed and the lock is released
 * automatically — no manual cleanup is required and no crashed worker can wedge
 * the sweep permanently.
 *
 * <p>Acquisition failure is a normal, non-error outcome: it means another replica
 * already holds the lock for this tick.
 */
@Component
class DistributedSweepLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedSweepLock.class);

    /** Stable advisory lock key: ASCII bytes of "SLA_SW" packed into a long. */
    private static final long LOCK_KEY = 0x534C415F5357L; // "SLA_SW"

    private final JdbcTemplate jdbcTemplate;

    DistributedSweepLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempts to acquire the SLA sweep advisory lock.
     *
     * @return {@code true} if this process now holds the lock; {@code false} if
     *         another replica holds it — the caller should exit without error
     */
    boolean tryAcquire() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        boolean held = Boolean.TRUE.equals(acquired);
        if (!held) {
            log.debug("sla.sweep_lock_skipped: another replica holds the advisory lock");
        }
        return held;
    }

    /**
     * Releases the advisory lock.  Always called in a {@code finally} block so the
     * connection is never left holding a stale lock after a sweep completes.
     */
    void release() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (Exception ex) {
            log.warn("sla.sweep_lock_release_failed: {}", ex.getMessage());
        }
    }
}

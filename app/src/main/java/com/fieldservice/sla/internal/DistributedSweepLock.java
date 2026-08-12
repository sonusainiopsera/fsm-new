package com.fieldservice.sla.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Distributed sweep lock backed by a PostgreSQL session-level advisory lock.
 *
 * <p>Uses {@code pg_try_advisory_lock} keyed on a stable 64-bit hash of the sweep name.
 * A session-level advisory lock is automatically released when the database connection
 * is closed — crashed processes self-heal because the pool recycles or closes the
 * connection, releasing the lock without any explicit release call.
 *
 * <p>Callers MUST release in a {@code finally} block to return the connection to the pool
 * cleanly; {@link #release()} issues {@code pg_advisory_unlock} and is idempotent.
 */
@Component
class DistributedSweepLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedSweepLock.class);

    /** Stable 64-bit key for the SLA sweep lock — must never change after deployment. */
    static final long LOCK_KEY = 0x736c61737765657AL; // "slasweep" as ASCII bytes → long

    private final JdbcTemplate jdbc;

    DistributedSweepLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to acquire the session-level advisory lock.
     *
     * @return {@code true} if the lock was acquired by this call; {@code false} if
     *         another session already holds it (the tick should exit immediately)
     */
    boolean tryAcquire() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("sla_sweep_lock acquired key={}", Long.toHexString(LOCK_KEY));
            return true;
        }
        log.debug("sla_sweep_lock not acquired — another replica holds it");
        return false;
    }

    /**
     * Releases the session-level advisory lock. Idempotent — safe to call even if the
     * lock was never acquired or was already released.
     */
    void release() {
        try {
            jdbc.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
            log.debug("sla_sweep_lock released key={}", Long.toHexString(LOCK_KEY));
        } catch (Exception ex) {
            log.warn("sla_sweep_lock release failed (lock may have already expired): {}", ex.getMessage());
        }
    }
}

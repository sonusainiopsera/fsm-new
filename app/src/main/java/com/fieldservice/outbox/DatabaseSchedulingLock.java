package com.fieldservice.outbox;

import com.fieldservice.platform.outbox.SchedulingLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Lease-based distributed lock backed by the {@code scheduler_lock} table.
 *
 * <p>Uses a PostgreSQL {@code INSERT … ON CONFLICT DO UPDATE … WHERE} to atomically
 * acquire or renew the lease only when the existing lease has expired or this process
 * already holds it. All timestamps use database {@code now()} so replica clock skew
 * cannot produce two simultaneous leaders.
 *
 * <p>The holder identity is a UUID generated once at startup; it is unique per process
 * and survives across lease renewals within the same JVM lifetime.
 */
@Component
public class DatabaseSchedulingLock implements SchedulingLock {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchedulingLock.class);

    private final JdbcTemplate jdbcTemplate;
    private final String holder = UUID.randomUUID().toString();

    public DatabaseSchedulingLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean runIfLeader(String lockName, Duration leaseDuration, Runnable task) {
        if (!tryAcquireLease(lockName, leaseDuration)) {
            log.debug("lock={} holder={} — lease held by another replica; skipping", lockName, holder);
            return false;
        }
        log.debug("lock={} holder={} — lease acquired; executing sweep", lockName, holder);
        task.run();
        return true;
    }

    /** Returns {@code true} when this process acquired or renewed the named lease. */
    public boolean tryAcquireLease(String lockName, Duration leaseDuration) {
        String interval = leaseDuration.toMillis() + " milliseconds";
        int affected = jdbcTemplate.update("""
                INSERT INTO scheduler_lock (lock_name, holder, acquired_at, expires_at)
                VALUES (?, ?, now(), now() + ?::interval)
                ON CONFLICT (lock_name) DO UPDATE
                SET holder      = EXCLUDED.holder,
                    acquired_at = now(),
                    expires_at  = now() + ?::interval
                WHERE scheduler_lock.expires_at  < now()
                   OR scheduler_lock.holder = EXCLUDED.holder
                """,
                lockName, holder, interval, interval);
        return affected > 0;
    }

    public String getHolder() {
        return holder;
    }
}

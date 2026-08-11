package com.fieldservice.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC-backed {@link SchedulingLock} using the {@code scheduler_lock} table.
 *
 * <p>Acquisition strategy: try to INSERT a new row; on conflict UPDATE the row only when
 * the stored {@code expires_at} is in the past (using database time {@code NOW()}), or when
 * this replica is already the holder.  Both cases use a single conditional UPDATE so the
 * operation is a compare-and-swap with no race between the check and the write.
 *
 * <p>All timestamp comparisons use {@code NOW()} evaluated by the database, not by the
 * application, so replica clock skew cannot create two simultaneous leaders.
 */
@Component
public class JdbcSchedulingLock implements SchedulingLock {

    private static final Logger log = LoggerFactory.getLogger(JdbcSchedulingLock.class);

    private static final String UPSERT = """
            INSERT INTO scheduler_lock (lock_name, holder, acquired_at, expires_at)
            VALUES (?, ?, NOW(), NOW() + (? * INTERVAL '1 second'))
            ON CONFLICT (lock_name) DO UPDATE
               SET holder = EXCLUDED.holder,
                   acquired_at = NOW(),
                   expires_at  = NOW() + (? * INTERVAL '1 second')
             WHERE scheduler_lock.expires_at < NOW()
                OR scheduler_lock.holder = EXCLUDED.holder
            """;

    private final JdbcTemplate jdbc;

    public JdbcSchedulingLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void runIfLeader(String lockName, String holder, int leaseSecs, Runnable body) {
        try {
            int updated = jdbc.update(UPSERT, lockName, holder, leaseSecs, leaseSecs);
            if (updated > 0) {
                log.debug("scheduler_lock acquired lock={} holder={}", lockName, holder);
                body.run();
            } else {
                log.debug("scheduler_lock not acquired lock={} holder={}", lockName, holder);
            }
        } catch (Exception e) {
            log.warn("scheduler_lock acquisition failed lock={}: {}", lockName, e.getMessage());
        }
    }
}

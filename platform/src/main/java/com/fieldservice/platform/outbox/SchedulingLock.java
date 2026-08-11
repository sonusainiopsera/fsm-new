package com.fieldservice.platform.outbox;

/**
 * Distributed lock ensuring exactly one replica executes a guarded periodic sweep
 * per lease window.
 *
 * <p>Leases are compared using database time, not application clock, so clock skew
 * between replicas cannot produce two simultaneous holders.
 *
 * <p>Register an implementation as a Spring bean; the default implementation is
 * {@link JdbcSchedulingLock} in the {@code platform.outbox} package.
 */
public interface SchedulingLock {

    /**
     * Acquires the named lock and runs {@code body} if this replica is the current leader.
     * If another replica holds a non-expired lease the body is skipped silently.
     *
     * @param lockName  unique identifier for the periodic sweep (e.g. {@code "outbox-poller"})
     * @param holder    stable identifier for this replica (e.g. hostname + PID)
     * @param leaseSecs lease duration in seconds
     * @param body      the guarded work; called only when the lock is held
     */
    void runIfLeader(String lockName, String holder, int leaseSecs, Runnable body);
}

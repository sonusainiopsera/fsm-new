package com.fieldservice.platform.outbox;

import java.time.Duration;

/**
 * Distributed lock primitive that guarantees at most one worker replica executes a
 * periodic sweep per lease window.
 *
 * <p>Leases use database time exclusively so clock skew between replicas cannot
 * produce two simultaneous leaders.
 *
 * <p>Intended usage:
 * <pre>{@code
 *   schedulingLock.runIfLeader("sla-evaluator", Duration.ofSeconds(30), () -> {
 *       slaEvaluatorService.runSweep();
 *   });
 * }</pre>
 */
public interface SchedulingLock {

    /**
     * Acquires or renews the named lease and, if successful, executes {@code task}.
     *
     * @param lockName      unique name identifying the periodic sweep
     * @param leaseDuration how long this lease remains valid after acquisition
     * @param task          the sweep body to run if the lease is held
     * @return {@code true} if the lease was acquired and {@code task} was executed,
     *         {@code false} if another holder has a valid lease (normal, non-error outcome)
     */
    boolean runIfLeader(String lockName, Duration leaseDuration, Runnable task);
}

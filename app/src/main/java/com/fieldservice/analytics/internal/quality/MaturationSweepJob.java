package com.fieldservice.analytics.internal.quality;

import com.fieldservice.platform.outbox.JdbcSchedulingLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.net.InetAddress;

/**
 * Daily sweep that promotes PROVISIONAL closure-projection rows to MATURED once
 * the 30-day observation window has elapsed.
 *
 * <p>Uses the same distributed-lock pattern as the SLA sweep job: only the leader
 * replica executes the promotion, preventing duplicate updates across a cluster.
 *
 * <p>Promotion is an idempotent conditional update — rows already MATURED are
 * unaffected. A failed sweep is retried on the next scheduled run without leaving
 * rows in a partially promoted state.
 */
@Component
@Profile("worker")
public class MaturationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(MaturationSweepJob.class);
    private static final String LOCK_NAME = "analytics-maturation-sweep";
    private static final int LEASE_SECS = 120;

    private final ClosureProjectionRepository closureRepo;
    private final JdbcSchedulingLock          schedulingLock;
    private final Clock                       clock;

    public MaturationSweepJob(ClosureProjectionRepository closureRepo,
                               JdbcSchedulingLock          schedulingLock,
                               Clock                       clock) {
        this.closureRepo    = closureRepo;
        this.schedulingLock = schedulingLock;
        this.clock          = clock;
    }

    @Scheduled(cron = "${app.analytics.maturation-sweep-cron:0 0 1 * * *}")
    public void sweep() {
        String holder = holderName();
        schedulingLock.runIfLeader(LOCK_NAME, holder, LEASE_SECS, this::doSweep);
    }

    @Transactional
    void doSweep() {
        int promoted = closureRepo.promoteEligible(clock.instant());
        log.info("analytics_maturation_sweep_complete promoted={}", promoted);
    }

    private static String holderName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

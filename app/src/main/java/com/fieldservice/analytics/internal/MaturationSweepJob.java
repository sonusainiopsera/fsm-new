package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.internal.quality.CohortMaturityResolver;
import com.fieldservice.platform.outbox.SchedulingLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Daily maturation sweep: promotes PROVISIONAL closure projections to MATURED
 * when their 30-day observation window has elapsed (WO-164).
 *
 * <p>Runs only on the {@code worker} Spring profile. Acquires the distributed lock
 * so exactly one worker instance runs the sweep at a time.
 */
@Component
@Profile("worker")
class MaturationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(MaturationSweepJob.class);

    static final String LOCK_NAME = "quality.maturation.sweep";
    static final Duration LEASE_DURATION = Duration.ofSeconds(120);

    private final CohortMaturityResolver maturityResolver;
    private final MetricDebounceRegistry debounceRegistry;
    private final SchedulingLock schedulingLock;

    MaturationSweepJob(
            CohortMaturityResolver maturityResolver,
            MetricDebounceRegistry debounceRegistry,
            SchedulingLock schedulingLock) {
        this.maturityResolver = maturityResolver;
        this.debounceRegistry = debounceRegistry;
        this.schedulingLock = schedulingLock;
    }

    @Scheduled(fixedDelayString = "${app.analytics.quality.maturation-interval-ms:86400000}")
    void sweep() {
        schedulingLock.runIfLeader(LOCK_NAME, LEASE_DURATION, () -> {
            log.info("quality.maturation.sweep.start");
            try {
                int promoted = maturityResolver.promoteMaturedRows();
                if (promoted > 0) {
                    QualityMetricKeys.ALL.forEach(debounceRegistry::markDirty);
                    log.info("quality.maturation.sweep.complete: promoted={}", promoted);
                } else {
                    log.debug("quality.maturation.sweep.no_promotions");
                }
            } catch (Exception ex) {
                log.error("quality.maturation.sweep.error — {}", ex.getMessage(), ex);
            }
        });
    }
}

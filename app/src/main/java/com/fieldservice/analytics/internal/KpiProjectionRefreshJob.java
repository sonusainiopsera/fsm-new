package com.fieldservice.analytics.internal;

import com.fieldservice.platform.outbox.SchedulingLock;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Scheduled flush loop for KPI projection recomputation (WO-161).
 *
 * <p>Runs only on the {@code worker} Spring profile — never on API-only nodes (per
 * the constraint that refresh work must not consume request-serving threads).
 *
 * <p>On each 1-second tick:
 * <ol>
 *   <li>Calls {@link MetricDebounceRegistry#drainExpired()} to get metrics ready for refresh.</li>
 *   <li>For each expired metric, acquires the distributed lock so exactly one worker replica
 *       recomputes per metric per tick.</li>
 *   <li>Calls {@link KpiProjectionService#recomputeAndPersist(String)} under the lock.</li>
 * </ol>
 *
 * <p>Fixed rate (1 second) is deliberate: it fires frequently to drain the debounce window
 * as soon as the 15-second delay passes, but the work is only done when the debounce
 * registry actually has expired entries — it is not a busy loop.
 */
@Component
@Profile("worker")
@EnableScheduling
class KpiProjectionRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(KpiProjectionRefreshJob.class);

    static final String LOCK_PREFIX = "analytics.kpi.";

    @Value("${app.analytics.refresh.lease-seconds:20}")
    private int leaseSeconds;

    private final MetricDebounceRegistry debounceRegistry;
    private final KpiProjectionService projectionService;
    private final SchedulingLock schedulingLock;
    private final AnalyticsMetrics analyticsMetrics;

    KpiProjectionRefreshJob(
            MetricDebounceRegistry debounceRegistry,
            KpiProjectionService projectionService,
            SchedulingLock schedulingLock,
            AnalyticsMetrics analyticsMetrics) {
        this.debounceRegistry  = debounceRegistry;
        this.projectionService = projectionService;
        this.schedulingLock    = schedulingLock;
        this.analyticsMetrics  = analyticsMetrics;
    }

    @Scheduled(fixedDelayString = "${app.analytics.refresh.flush-interval-ms:1000}")
    void flush() {
        List<String> expired = debounceRegistry.drainExpired();
        if (expired.isEmpty()) return;

        log.debug("analytics.flush.start: count={}", expired.size());

        for (String metricKey : expired) {
            String lockName = LOCK_PREFIX + metricKey;
            try {
                Timer timer = analyticsMetrics.refreshLagTimer(metricKey);
                timer.record(() ->
                        schedulingLock.runIfLeader(lockName, Duration.ofSeconds(leaseSeconds),
                                () -> projectionService.recomputeAndPersist(metricKey)));
            } catch (Exception ex) {
                log.error("analytics.flush.error: metricKey={} — {}", metricKey, ex.getMessage(), ex);
            }
        }
    }
}

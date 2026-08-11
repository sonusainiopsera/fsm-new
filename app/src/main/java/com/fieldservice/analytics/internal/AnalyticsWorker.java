package com.fieldservice.analytics.internal;

import com.fieldservice.platform.outbox.SchedulingLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;

/**
 * Scheduled worker that flushes the debounce registry and triggers KPI refreshes.
 *
 * <p>Only active on the {@code worker} Spring profile — never on API replicas.
 * A distributed lock ({@link SchedulingLock}) ensures exactly one replica
 * computes each metric per debounce window, regardless of worker replica count.
 *
 * <p>Tick interval: 1 second (configurable via {@code app.analytics.worker-interval-ms}).
 * Processed-event retention purge runs every hour.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "app.analytics", name = "enabled", havingValue = "true", matchIfMissing = true)
class AnalyticsWorker {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsWorker.class);
    private static final String LOCK_NAME    = "analytics-kpi-refresh";
    private static final int    LEASE_SECS   = 2; // short lease — 1s tick

    private final MetricDebounceRegistry        debounceRegistry;
    private final KpiProjectionRefreshService   refreshService;
    private final KpiProjectionRepository       projectionRepository;
    private final ProcessedEventRepository      processedEventRepository;
    private final AnalyticsMetrics              metrics;
    private final SchedulingLock                schedulingLock;
    private final String                        holder;

    AnalyticsWorker(MetricDebounceRegistry        debounceRegistry,
                    KpiProjectionRefreshService   refreshService,
                    KpiProjectionRepository       projectionRepository,
                    ProcessedEventRepository      processedEventRepository,
                    AnalyticsMetrics              metrics,
                    SchedulingLock                schedulingLock) {
        this.debounceRegistry         = debounceRegistry;
        this.refreshService           = refreshService;
        this.projectionRepository     = projectionRepository;
        this.processedEventRepository = processedEventRepository;
        this.metrics                  = metrics;
        this.schedulingLock           = schedulingLock;
        this.holder                   = resolveHolder();
    }

    /** Main tick: drain eligible metrics and refresh each one under a distributed lock. */
    @Scheduled(fixedDelayString = "${app.analytics.worker-interval-ms:1000}")
    void tick() {
        List<String> eligible = debounceRegistry.drainEligible();
        if (eligible.isEmpty()) {
            return;
        }

        for (String metricKey : eligible) {
            schedulingLock.runIfLeader(LOCK_NAME + ":" + metricKey, holder, LEASE_SECS, () -> {
                try {
                    refreshService.refresh(metricKey);
                } catch (Exception e) {
                    log.error("analytics_worker_refresh_failed metric_key={} error={}",
                            metricKey, e.getMessage());
                }
            });
        }

        // Update staleness gauges after refresh pass
        try {
            List<KpiProjectionEntity> all = projectionRepository.findAllOrderByDataAsOfAsc();
            metrics.updateStalenessBulk(all);
        } catch (Exception e) {
            log.warn("analytics_staleness_update_failed error={}", e.getMessage());
        }
    }

    /** Hourly: purge processed_event rows older than 30 days. */
    @Scheduled(fixedDelayString = "${app.analytics.retention-purge-interval-ms:3600000}")
    void purgeProcessedEvents() {
        try {
            java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(30));
            int deleted = processedEventRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("analytics_processed_event_purge deleted={}", deleted);
            }
        } catch (Exception e) {
            log.warn("analytics_processed_event_purge_failed error={}", e.getMessage());
        }
    }

    private static String resolveHolder() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "analytics-worker-" + System.nanoTime();
        }
    }
}

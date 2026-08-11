package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-metric debounce registry: coalesces bursts of events into at most one
 * recomputation per {@link #DEBOUNCE_WINDOW} window.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>On {@link #enqueue}: record the earliest instant at which this metric
 *       is eligible for recomputation ({@code now + DEBOUNCE_WINDOW}), but only
 *       if the metric is not already queued — {@code putIfAbsent} coalesces all
 *       events within a burst into a single pending entry.</li>
 *   <li>On {@link #drainEligible}: remove and return all metrics whose eligibility
 *       instant has passed. The {@link AnalyticsWorker} calls this every second
 *       and triggers one refresh per drained metric.</li>
 * </ol>
 *
 * <p>Subsequent event after a window expires: the metric is absent from the map,
 * so {@code putIfAbsent} inserts a fresh eligibility instant — exactly one more
 * recomputation is triggered after the new 15-second window.
 *
 * <p>Thread safety: all state is held in a {@link ConcurrentHashMap}; individual
 * {@code putIfAbsent} and {@code remove} calls are atomic.
 *
 * <p>Clock is injected for deterministic unit tests.
 */
@Component
class MetricDebounceRegistry {

    static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(MetricDebounceRegistry.class);

    private final ConcurrentHashMap<String, Instant> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    MetricDebounceRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Enqueues {@code metricKey} for recomputation after the debounce window.
     * If the key is already pending, this call is a no-op — the first event
     * in the burst sets the timer.
     */
    void enqueue(String metricKey) {
        Instant eligibleAt = clock.instant().plus(DEBOUNCE_WINDOW);
        Instant prior = pending.putIfAbsent(metricKey, eligibleAt);
        if (prior == null) {
            log.debug("analytics_debounce_enqueued metric_key={} eligible_at={}", metricKey, eligibleAt);
        }
    }

    /**
     * Returns and removes all metric keys whose debounce window has elapsed.
     * Called by the scheduler every second.
     */
    List<String> drainEligible() {
        Instant now = clock.instant();
        List<String> eligible = new ArrayList<>();
        for (var entry : pending.entrySet()) {
            if (!now.isBefore(entry.getValue())) {
                if (pending.remove(entry.getKey(), entry.getValue())) {
                    eligible.add(entry.getKey());
                    log.debug("analytics_debounce_flushed metric_key={}", entry.getKey());
                }
            }
        }
        return eligible;
    }

    /** Returns the number of metrics currently pending recomputation. */
    int pendingCount() {
        return pending.size();
    }
}

package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-metric debounce registry for KPI recomputation (WO-161).
 *
 * <p>Contract:
 * <ul>
 *   <li>The first {@link #markDirty} call for a metric within a debounce window records
 *       the {@code eligibleAt = now + DEBOUNCE_WINDOW} instant and no further calls
 *       within that window change it (earliest-wins).</li>
 *   <li>{@link #drainExpired()} returns the set of metric keys whose eligibleAt has
 *       passed and removes them from the registry atomically. The caller is responsible
 *       for recomputing each returned key.</li>
 *   <li>An event burst of N events for the same metric within one window triggers at
 *       most one drain entry — coalescing is the entire point.</li>
 * </ul>
 *
 * <p>{@link Clock} is injected for deterministic unit testing.
 */
@Component
class MetricDebounceRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetricDebounceRegistry.class);

    static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(15);

    private final ConcurrentHashMap<String, Instant> eligibleAt = new ConcurrentHashMap<>();
    private final Clock clock;

    MetricDebounceRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Marks a metric dirty (needs recomputation).
     *
     * <p>Uses {@code putIfAbsent} so only the first call within a window sets the
     * eligible-at time. Subsequent calls in the same burst are no-ops.
     *
     * @param metricKey the metric to mark dirty
     */
    void markDirty(String metricKey) {
        Instant eligible = clock.instant().plus(DEBOUNCE_WINDOW);
        Instant existing = eligibleAt.putIfAbsent(metricKey, eligible);
        if (existing == null) {
            log.debug("analytics.debounce.marked: metricKey={} eligibleAt={}", metricKey, eligible);
        }
    }

    /**
     * Returns and removes all metric keys whose debounce window has elapsed.
     *
     * <p>Thread-safe: uses {@code entrySet().removeIf} with the ConcurrentHashMap's
     * segment-level locking. A metric removed here will not appear in the next drain
     * unless {@link #markDirty} is called again.
     *
     * @return list of metric keys ready for recomputation (may be empty)
     */
    List<String> drainExpired() {
        Instant now = clock.instant();
        List<String> expired = new ArrayList<>();

        eligibleAt.entrySet().removeIf(entry -> {
            if (!entry.getValue().isAfter(now)) {
                expired.add(entry.getKey());
                return true;
            }
            return false;
        });

        if (!expired.isEmpty()) {
            log.debug("analytics.debounce.drained: count={} keys={}", expired.size(), expired);
        }
        return expired;
    }

    /** Returns the number of metrics currently pending recomputation. Visible for metrics. */
    int pendingCount() {
        return eligibleAt.size();
    }

    /** Returns a snapshot of currently-pending metric keys. Test/observability use only. */
    Set<String> pendingKeys() {
        return Set.copyOf(eligibleAt.keySet());
    }
}

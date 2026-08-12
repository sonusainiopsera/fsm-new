package com.fieldservice.analytics.internal;

import java.util.List;

/**
 * Metric key constants for SLA compliance and resolution time projections (WO-162).
 *
 * <p>No SLA threshold, priority tier name or hour target appears in this class or
 * anywhere in the analytics module. All thresholds come from sla_policy at runtime.
 *
 * <p>Segmented by priority: each metric has one kpi_projection row per
 * (segment_key = priority | "ALL", window_key = ROLLING_{N}D).
 * Delta rows use window_key = ROLLING_{N}D_DELTA, carrying current value as numerator
 * and prior-period value as denominator so the widget layer never recomputes.
 */
final class SlaMetricKeys {

    /** Compliance rate: compliant / total_closed, per priority and ALL rollup. */
    static final String COMPLIANCE_RATE   = "sla.compliance.rate";

    /** Mean elapsed minutes from creation to closure, per priority and ALL rollup. */
    static final String RESOLUTION_MEAN   = "sla.resolution.mean";

    /** Median elapsed minutes from creation to closure, per priority and ALL rollup. */
    static final String RESOLUTION_MEDIAN = "sla.resolution.median";

    /** Total breach count per priority and ALL rollup. */
    static final String BREACH_COUNT      = "sla.breach.count";

    /** Rolling 7-day window key. */
    static final String WINDOW_7D   = "ROLLING_7D";
    /** Rolling 30-day window key. */
    static final String WINDOW_30D  = "ROLLING_30D";
    /** Rolling 90-day window key. */
    static final String WINDOW_90D  = "ROLLING_90D";

    /** Suffix appended to window key to identify prior-period delta rows. */
    static final String DELTA_SUFFIX = "_DELTA";

    /** Segment key for the weighted all-priorities rollup. */
    static final String SEGMENT_ALL = KpiAggregationQueries.SEGMENT_ALL;

    /** Maturity value used when no captured baseline row exists for a segment (BR-30). */
    static final String MATURITY_BASELINE_PENDING = "BASELINE_PENDING";
    /** Maturity value once a baseline row exists and comparison is possible. */
    static final String MATURITY_CURRENT          = "CURRENT";

    /** All SLA metric keys — any of these being marked dirty recomputes all windows. */
    static final List<String> ALL_METRIC_KEYS = List.of(
            COMPLIANCE_RATE, RESOLUTION_MEAN, RESOLUTION_MEDIAN, BREACH_COUNT);

    /** All windows (current period), in ascending order. */
    static final List<String> WINDOWS = List.of(WINDOW_7D, WINDOW_30D, WINDOW_90D);

    /** Returns the number of days for a given window key. */
    static int windowDays(String windowKey) {
        // Strip DELTA suffix if present before matching
        String base = windowKey.endsWith(DELTA_SUFFIX)
                ? windowKey.substring(0, windowKey.length() - DELTA_SUFFIX.length())
                : windowKey;
        return switch (base) {
            case WINDOW_7D  -> 7;
            case WINDOW_30D -> 30;
            case WINDOW_90D -> 90;
            default -> throw new IllegalArgumentException("Unknown window key: " + windowKey);
        };
    }

    /** Returns the delta window key for a given main window key. */
    static String deltaWindowKey(String windowKey) {
        return windowKey + DELTA_SUFFIX;
    }

    private SlaMetricKeys() {}
}

package com.fieldservice.analytics.internal.workforce;

import java.util.List;

/**
 * Metric key constants for workforce utilization and throughput KPI projections (WO-163).
 *
 * <h3>Formula definitions (documented here per AC-1/2)</h3>
 * <ul>
 *   <li><b>Utilization rate</b> = sum(logged_field_minutes) / sum(shift_minutes) per technician per
 *       ISO week, team rollup = SUM(numerators) / SUM(denominators), not mean of per-technician
 *       rates (AC-1).</li>
 *   <li><b>Jobs per day</b> = closure_events / active_technician_days where an active technician-day
 *       is a day on which the technician had a rostered shift or any logged field time (AC-2).
 *       Technicians with zero active days are excluded from the denominator entirely.</li>
 * </ul>
 *
 * <h3>Data classification (BR-23)</h3>
 * Projection rows store technician UUIDs only. Display names are resolved at render time
 * through the authorized workforce read port. No personal data in analytics projections.
 *
 * <h3>Reference threshold (AC-6)</h3>
 * The 70 percent utilization objective from O3 appears only as a display reference line.
 * It is never encoded as a test-failing threshold on production data.
 */
final class WorkforceMetricKeys {

    /** Weekly utilization rate: logged field hours / total shift hours, per technician or team rollup. */
    static final String UTILIZATION_RATE = "workforce.utilization.rate";

    /** Daily throughput: work order closures per active technician-day. */
    static final String JOBS_PER_DAY     = "workforce.jobs_per_day";

    /** Rolling 7-day window key. */
    static final String WINDOW_7D  = "ROLLING_7D";
    /** Rolling 30-day window key. */
    static final String WINDOW_30D = "ROLLING_30D";
    /** Rolling 90-day window key. */
    static final String WINDOW_90D = "ROLLING_90D";

    /** Suffix for delta comparison rows. */
    static final String DELTA_SUFFIX = "_DELTA";

    /** Segment key for the team-level rollup. */
    static final String SEGMENT_ALL = "ALL";

    /** Maturity value when no baseline row has been captured yet (BR-30). */
    static final String MATURITY_BASELINE_PENDING = "BASELINE_PENDING";
    /** Maturity value once a baseline row exists. */
    static final String MATURITY_CURRENT          = "CURRENT";

    /** All workforce metric keys used for debounce routing. */
    static final List<String> ALL_METRIC_KEYS = List.of(UTILIZATION_RATE, JOBS_PER_DAY);

    /** All rolling windows in ascending order. */
    static final List<String> WINDOWS = List.of(WINDOW_7D, WINDOW_30D, WINDOW_90D);

    static int windowDays(String windowKey) {
        String base = windowKey.endsWith(DELTA_SUFFIX)
                ? windowKey.substring(0, windowKey.length() - DELTA_SUFFIX.length())
                : windowKey;
        return switch (base) {
            case WINDOW_7D  -> 7;
            case WINDOW_30D -> 30;
            case WINDOW_90D -> 90;
            default -> throw new IllegalArgumentException("Unknown workforce window key: " + windowKey);
        };
    }

    static String deltaWindowKey(String windowKey) {
        return windowKey + DELTA_SUFFIX;
    }

    private WorkforceMetricKeys() {}
}

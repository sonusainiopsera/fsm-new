package com.fieldservice.analytics.internal;

/**
 * Metric key constants for first-time fix quality metrics (WO-164).
 */
final class QualityMetricKeys {

    /** Matured first-time fix rate. */
    static final String FTF_MATURED = "quality.first_time_fix.matured";

    /**
     * Provisional companion rate. Stored with segment_key="PROVISIONAL" so the API layer
     * always carries the maturity label (BR-30).
     */
    static final String FTF_PROVISIONAL = "quality.first_time_fix.provisional";

    /** Total count of repeat visit links (all-time). */
    static final String REPEAT_VISIT_COUNT = "quality.repeat_visit.count";

    /** Count of work orders excluded from FTF due to missing asset or fault identity. */
    static final String UNCLASSIFIABLE_COUNT = "quality.unclassifiable.count";

    /** Segment key for the provisional companion projection. */
    static final String SEGMENT_PROVISIONAL = "PROVISIONAL";

    /** All quality metric keys — used to mark dirty after maturation sweep. */
    static final java.util.List<String> ALL = java.util.List.of(
            FTF_MATURED, FTF_PROVISIONAL, REPEAT_VISIT_COUNT, UNCLASSIFIABLE_COUNT);

    private QualityMetricKeys() {}
}

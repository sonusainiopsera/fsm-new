package com.fieldservice.analytics.internal;

import java.util.List;

/**
 * Metric key constants for CSAT satisfaction analytics (WO-173).
 */
final class CsatMetricKeys {

    /** Rolling 90-day mean CSAT score (1–5 scale). */
    static final String CSAT_MEAN_SCORE_90D = "csat.mean_score.90d";

    /** Rolling 90-day survey response rate (answered / issued). */
    static final String CSAT_RESPONSE_RATE_90D = "csat.response_rate.90d";

    /** All CSAT metric keys — marked dirty when a response is recorded. */
    static final List<String> ALL = List.of(CSAT_MEAN_SCORE_90D, CSAT_RESPONSE_RATE_90D);

    private CsatMetricKeys() {}
}

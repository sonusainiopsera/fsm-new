package com.fieldservice.dispatch.scoring;

import java.util.List;
import java.util.UUID;

/**
 * Scored and ranked output for one eligible technician.
 *
 * @param technicianId    identifies the technician
 * @param compositeScore  weighted-average composite in [0, 1];
 *                        0.0 when all active weights are zero (neutral ordering)
 * @param breakdown       per-factor detail, one entry per active factor
 * @param degraded        true when any factor contributed a degraded (fallback) value
 */
public record ScoredCandidate(
        UUID technicianId,
        double compositeScore,
        boolean degraded,
        List<FactorBreakdown> breakdown
) {
    public ScoredCandidate {
        breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    }
}

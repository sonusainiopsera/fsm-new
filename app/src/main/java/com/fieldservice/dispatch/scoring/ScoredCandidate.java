package com.fieldservice.dispatch.scoring;

import java.util.List;
import java.util.UUID;

/**
 * Output of the scoring engine for one eligible candidate.
 *
 * @param technicianId     candidate identifier
 * @param compositeScore   normalised composite score in [0.0, 1.0]
 * @param breakdown        per-factor breakdown, one entry per active factor
 * @param degraded         true when at least one factor used a fallback (degraded) value
 */
public record ScoredCandidate(
        UUID technicianId,
        double compositeScore,
        List<FactorBreakdown> breakdown,
        boolean degraded) {

    public ScoredCandidate {
        breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    }
}

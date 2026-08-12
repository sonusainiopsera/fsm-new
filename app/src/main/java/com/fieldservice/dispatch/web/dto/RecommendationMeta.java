package com.fieldservice.dispatch.web.dto;

import com.fieldservice.workorder.web.AssignmentWarning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business metadata returned in every recommendation response envelope.
 */
public record RecommendationMeta(
        UUID                      snapshotId,
        Instant                   generatedAt,
        String                    weightSetVersion,
        boolean                   travelEstimateDegraded,
        boolean                   partsDataDegraded,
        int                       candidatePoolSize,
        boolean                   truncated,
        List<ExclusionSummaryDto> exclusionSummary,
        List<AssignmentWarning>   partsWarnings) {

    /** Backward-compatible constructor for callers that do not yet supply parts warnings. */
    public RecommendationMeta(UUID snapshotId, Instant generatedAt, String weightSetVersion,
                              boolean travelEstimateDegraded, boolean partsDataDegraded,
                              int candidatePoolSize, boolean truncated,
                              List<ExclusionSummaryDto> exclusionSummary) {
        this(snapshotId, generatedAt, weightSetVersion,
             travelEstimateDegraded, partsDataDegraded,
             candidatePoolSize, truncated, exclusionSummary, List.of());
    }
}

package com.fieldservice.dispatch.web.dto;

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
        List<ExclusionSummaryDto> exclusionSummary) {
}

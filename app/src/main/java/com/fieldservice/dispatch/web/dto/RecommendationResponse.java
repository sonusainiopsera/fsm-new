package com.fieldservice.dispatch.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Platform envelope for the recommendations endpoint.
 *
 * <pre>{@code
 * {
 *   "data": [...],
 *   "page": { "size": 20, "hasNext": true },
 *   "links": { "next": "<cursor url>" },
 *   "meta": { "snapshotId": "...", ... }
 * }
 * }</pre>
 */
public record RecommendationResponse(
        List<CandidateDto> data,
        Page page,
        Links links,
        Meta meta
) {
    public record Page(int size, boolean hasNext) {}

    public record Links(String next) {}

    public record Meta(
            UUID snapshotId,
            Instant generatedAt,
            String weightSetVersion,
            boolean travelEstimateDegraded,
            boolean partsDataDegraded,
            int candidatePoolSize,
            boolean truncated,
            List<ExclusionSummaryEntry> exclusionSummary,
            PartsWarning partsWarning
    ) {}
}

package com.fieldservice.workorder.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Compact prior service record for the asset history panel (WO-156 AC-2).
 *
 * <p>Scoped: only closed work orders the caller is entitled to see are returned.
 * Five most recent closed work orders are included, ordered by resolution date descending.
 */
public record AssetServiceHistoryItem(
        UUID workOrderId,
        String reference,
        Instant resolvedAt,
        String faultSummary,
        String resolutionSummary,
        List<String> partsUsed
) {
    public AssetServiceHistoryItem {
        partsUsed = partsUsed == null ? List.of() : List.copyOf(partsUsed);
    }
}

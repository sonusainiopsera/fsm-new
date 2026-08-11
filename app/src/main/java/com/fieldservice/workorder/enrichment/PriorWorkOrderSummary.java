package com.fieldservice.workorder.enrichment;

import java.util.List;
import java.util.UUID;

/**
 * Immutable summary of a single prior closed work order on the same asset,
 * used to ground AI prompts with real service history.
 *
 * <p>All fields may be {@code null} except {@code workOrderId}.
 *
 * @param workOrderId      the work order's UUID
 * @param faultDescription the fault description at the time of the job (may contain PII — must be redacted before leaving the platform)
 * @param resolutionNotes  technician resolution notes (may contain PII — must be redacted before leaving the platform)
 * @param partsConsumed    part names consumed during this job (catalogue text, not PII)
 */
public record PriorWorkOrderSummary(
        UUID workOrderId,
        String faultDescription,
        String resolutionNotes,
        List<String> partsConsumed
) {
    public PriorWorkOrderSummary {
        java.util.Objects.requireNonNull(workOrderId, "workOrderId");
        partsConsumed = partsConsumed == null ? List.of() : List.copyOf(partsConsumed);
    }
}

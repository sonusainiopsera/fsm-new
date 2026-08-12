package com.fieldservice.workorder.enrichment;

import java.util.UUID;

/**
 * A closed/completed work order on the same asset, included as historical context for AI grounding.
 */
public record PriorServiceEntry(
        UUID workOrderId,
        String workOrderReference,
        String faultCode,
        String faultCategory,
        String description) {
}

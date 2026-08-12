package com.fieldservice.copilot.internal;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.workorder.enrichment.WorkOrderEnrichmentPort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Retrieves and wraps the work-order enrichment context as a {@link GroundingContext}.
 * Delegates entirely to {@link WorkOrderEnrichmentPort} — this class must not access
 * asset or work-order repositories directly (enforced by the ArchUnit boundary test).
 */
@Service
class GroundingContextRetriever {

    private final WorkOrderEnrichmentPort enrichmentPort;
    private final CopilotGroundingProperties properties;

    GroundingContextRetriever(WorkOrderEnrichmentPort enrichmentPort, CopilotGroundingProperties properties) {
        this.enrichmentPort = enrichmentPort;
        this.properties     = properties;
    }

    /**
     * Loads grounding context for the given work order within the caller's scope.
     *
     * @throws GroundingUnavailableException if the work order is inaccessible or does not exist
     */
    GroundingContext retrieve(UUID workOrderId, AccessScope scope) {
        return enrichmentPort.loadContext(workOrderId, scope, properties.maxPriorWorkOrders())
                .map(GroundingContext::from)
                .orElseThrow(() -> new GroundingUnavailableException("WORK_ORDER_NOT_ACCESSIBLE"));
    }
}

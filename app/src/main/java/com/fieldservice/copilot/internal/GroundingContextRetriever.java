package com.fieldservice.copilot.internal;

import com.fieldservice.copilot.api.GroundingUnavailableException;
import com.fieldservice.copilot.api.SufficiencyVerdict;
import com.fieldservice.workorder.enrichment.WorkOrderEnrichmentPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Loads the grounding context for a work order by delegating to {@link WorkOrderEnrichmentPort}.
 *
 * <p>Does not access any repository directly — all data flows through the enrichment port,
 * satisfying AC-2 (no direct repository access from the copilot module).
 */
@Component
class GroundingContextRetriever {

    private final WorkOrderEnrichmentPort enrichmentPort;
    private final CopilotProperties properties;

    GroundingContextRetriever(WorkOrderEnrichmentPort enrichmentPort, CopilotProperties properties) {
        this.enrichmentPort = enrichmentPort;
        this.properties     = properties;
    }

    /**
     * Retrieves the grounding context for the given work order.
     *
     * @param workOrderId the target work order (scope-enforced by the enrichment port)
     * @return a package-private {@link GroundingContext} wrapping the raw enrichment data
     * @throws GroundingUnavailableException if the asset is missing
     * @throws com.fieldservice.platform.security.ScopedAccessDeniedException if the work order is out of scope
     */
    GroundingContext retrieve(UUID workOrderId) {
        var enrichment = enrichmentPort.loadContext(workOrderId, properties.getMaxPriorWorkOrders());

        if (enrichment.asset() == null) {
            throw new GroundingUnavailableException(
                    SufficiencyVerdict.ReasonCode.NO_ASSET_IDENTITY,
                    "Work order has no asset_id — grounding context unavailable");
        }

        return new GroundingContext(enrichment);
    }
}

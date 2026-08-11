package com.fieldservice.workorder.enrichment;

import java.util.UUID;

/**
 * Port for loading the full grounding context of a work order.
 *
 * <p>Consumers (currently the copilot package) call this port rather than reaching into
 * asset or work-order repositories directly, keeping the enrichment logic in one place.
 *
 * <p>The returned context carries raw (un-redacted) domain data.
 * Callers are responsible for passing it through the PII redactor before
 * any content crosses the platform trust boundary to an AI provider.
 */
public interface WorkOrderEnrichmentPort {

    /**
     * Loads the enrichment context for the given work order.
     *
     * <p>Access is scope-enforced: the current principal must be authorised to see the
     * work order. An out-of-scope or non-existent work order results in a
     * {@link com.fieldservice.platform.security.ScopedAccessDeniedException}.
     *
     * @param workOrderId the work order to enrich
     * @param maxPriorWorkOrders maximum number of prior closed work orders to include
     * @return the assembled enrichment context
     */
    WorkOrderEnrichmentContext loadContext(UUID workOrderId, int maxPriorWorkOrders);
}

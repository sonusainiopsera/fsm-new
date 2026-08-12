package com.fieldservice.workorder.enrichment;

import com.fieldservice.platform.security.AccessScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Public port for loading work-order enrichment context.
 * Copilot and other consumers depend on this interface; they must not access
 * asset or work-order repositories directly.
 */
public interface WorkOrderEnrichmentPort {

    /**
     * Loads the enrichment context for the given work order, applying the caller's
     * AccessScope as a query predicate so out-of-scope records are never materialised.
     * Returns empty when the work order does not exist or is not accessible to the caller.
     *
     * @param workOrderId       the work order to enrich
     * @param scope             the caller's access scope (applied as a query predicate)
     * @param maxPriorWorkOrders maximum number of prior closed work orders on the same asset to include
     * @return enrichment context, or empty if the work order is not accessible
     */
    Optional<WorkOrderContextData> loadContext(UUID workOrderId, AccessScope scope, int maxPriorWorkOrders);
}

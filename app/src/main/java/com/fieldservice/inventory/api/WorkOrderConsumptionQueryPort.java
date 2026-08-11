package com.fieldservice.inventory.api;

import java.util.UUID;

/**
 * Public read port for querying work order parts consumption state.
 *
 * <p>This is the only exported surface of the inventory module for consumption queries.
 * Callers must not import {@code inventory.application} or {@code domain.inventory} directly.
 */
public interface WorkOrderConsumptionQueryPort {

    /**
     * Returns {@code true} if any parts-consumption record for the given work order is in an
     * unreconciled state; {@code false} if all records are reconciled or none exist.
     *
     * <p>A work order with zero consumption records is considered reconciled (returns false).
     *
     * @param workOrderId the ID of the work order to check
     * @return true when at least one unreconciled consumption record exists
     */
    boolean hasUnreconciledConsumption(UUID workOrderId);
}

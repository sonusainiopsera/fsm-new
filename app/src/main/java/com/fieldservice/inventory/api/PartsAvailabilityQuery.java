package com.fieldservice.inventory.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Input to {@link StockQueryService#batchCheckAvailability}.
 *
 * <p>The caller (dispatch module) supplies:
 * <ul>
 *   <li>the work-order's required parts and quantities</li>
 *   <li>the vehicle stock location IDs for each eligible technician candidate</li>
 *   <li>the IDs of warehouses the dispatcher considers reachable for this job</li>
 * </ul>
 * Inventory remains free of workforce and geo knowledge — reachability decisions
 * are made by the caller, not by this module.
 *
 * <p>Zero-quantity required part lines are silently discarded before the lookup.
 *
 * @param requiredParts             parts and quantities required by the work order
 * @param candidateVehicleLocationIds vehicle stock location IDs for eligible candidates
 * @param reachableWarehouseLocationIds warehouse location IDs the caller considers reachable
 */
public record PartsAvailabilityQuery(
        List<RequiredPartQuantity> requiredParts,
        Set<UUID> candidateVehicleLocationIds,
        Set<UUID> reachableWarehouseLocationIds
) {
    public PartsAvailabilityQuery {
        requiredParts = requiredParts == null ? List.of() : List.copyOf(requiredParts);
        candidateVehicleLocationIds = candidateVehicleLocationIds == null
                ? Set.of() : Set.copyOf(candidateVehicleLocationIds);
        reachableWarehouseLocationIds = reachableWarehouseLocationIds == null
                ? Set.of() : Set.copyOf(reachableWarehouseLocationIds);
    }
}

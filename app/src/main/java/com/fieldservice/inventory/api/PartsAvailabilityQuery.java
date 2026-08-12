package com.fieldservice.inventory.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Batch availability query input.
 *
 * <p>The caller (dispatch) supplies:
 * <ul>
 *   <li>the required part quantities for the work order;</li>
 *   <li>all candidate vehicle location IDs (one per eligible technician);</li>
 *   <li>reachable warehouse location IDs (resolved by the dispatch layer via geo).</li>
 * </ul>
 *
 * @param requiredQuantities  map of part ID → required quantity; zero-quantity entries are ignored
 * @param vehicleLocationIds  candidate vehicle stock location IDs to assess
 * @param warehouseLocationIds reachable warehouse stock location IDs to check for collectability
 */
public record PartsAvailabilityQuery(
        Map<UUID, Integer> requiredQuantities,
        Set<UUID> vehicleLocationIds,
        Set<UUID> warehouseLocationIds) {

    public PartsAvailabilityQuery {
        if (requiredQuantities == null) requiredQuantities = Map.of();
        if (vehicleLocationIds  == null) vehicleLocationIds  = Set.of();
        if (warehouseLocationIds == null) warehouseLocationIds = Set.of();
        requiredQuantities = Map.copyOf(requiredQuantities);
        vehicleLocationIds = Set.copyOf(vehicleLocationIds);
        warehouseLocationIds = Set.copyOf(warehouseLocationIds);
    }
}

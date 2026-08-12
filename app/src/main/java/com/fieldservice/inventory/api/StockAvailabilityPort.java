package com.fieldservice.inventory.api;

/**
 * Read-only port for batched stock availability checks.
 *
 * <p>Exposes no mutating operations, preserving BR-16 and BR-17 stock invariants.
 * The dispatch module depends on this interface only; no inventory entity crosses the boundary.
 */
public interface StockAvailabilityPort {

    /**
     * Checks parts availability in batch across candidate vehicle locations and reachable warehouses.
     *
     * <p>Issues a bounded number of database queries independent of candidate count.
     * When {@code query.requiredParts()} is empty the implementation returns
     * {@link PartsAvailabilityResult#empty()} immediately.
     *
     * @param query required parts and candidate/warehouse location IDs
     * @return per-candidate availability classification, job verdict, and freshness timestamp
     */
    PartsAvailabilityResult batchCheckAvailability(PartsAvailabilityQuery query);
}

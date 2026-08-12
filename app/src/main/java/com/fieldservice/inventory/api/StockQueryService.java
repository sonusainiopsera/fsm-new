package com.fieldservice.inventory.api;

/**
 * Read-only availability query surface for the inventory module.
 *
 * <p>Dispatch consumes this interface exclusively; it must not access inventory domain
 * entities or repositories directly (enforced by ArchUnit).
 */
public interface StockQueryService {

    /**
     * Returns a per-candidate-location availability classification for the given work order
     * parts requirement.
     *
     * <p>The implementation issues a bounded number of SQL queries independent of
     * {@code query.vehicleLocationIds().size()} — results are cached briefly.
     *
     * @param query the set-based lookup parameters
     * @return availability result with freshness timestamp; never null
     */
    PartsAvailabilityResult queryAvailability(PartsAvailabilityQuery query);
}

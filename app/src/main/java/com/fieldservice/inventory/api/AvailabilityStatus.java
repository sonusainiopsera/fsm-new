package com.fieldservice.inventory.api;

/**
 * Per-candidate classification for the required parts of a single work order.
 *
 * <p>Ordered from best (FULLY_STOCKED) to worst (UNAVAILABLE):
 * <ol>
 *   <li>FULLY_STOCKED — all required parts are present in the candidate's vehicle location</li>
 *   <li>COLLECTABLE   — all missing parts are available in at least one reachable warehouse</li>
 *   <li>PARTIALLY_STOCKED — some missing parts are warehouse-collectable but not all</li>
 *   <li>UNAVAILABLE   — at least one required part cannot be sourced from vehicle or warehouse</li>
 * </ol>
 */
public enum AvailabilityStatus {
    FULLY_STOCKED,
    COLLECTABLE,
    PARTIALLY_STOCKED,
    UNAVAILABLE
}

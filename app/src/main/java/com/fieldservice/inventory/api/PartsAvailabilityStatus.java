package com.fieldservice.inventory.api;

/**
 * Per-candidate (or job-level) verdict for parts availability.
 *
 * <p>Ordering from best to worst:
 * <ol>
 *   <li>{@link #FULLY_STOCKED} — all required parts are in the candidate's vehicle stock.</li>
 *   <li>{@link #PARTIALLY_STOCKED} — some required parts are in the vehicle; shortfalls exist.</li>
 *   <li>{@link #COLLECTABLE} — vehicle stock is insufficient but reachable warehouses cover all shortfalls.</li>
 *   <li>{@link #UNAVAILABLE} — parts are neither in the vehicle nor in any reachable warehouse.</li>
 * </ol>
 *
 * <p>Parts availability is advisory only. It never becomes a hard assignment gate and
 * must never relax the certification gate (AC-8 / WO-151).
 */
public enum PartsAvailabilityStatus {
    FULLY_STOCKED,
    PARTIALLY_STOCKED,
    COLLECTABLE,
    UNAVAILABLE
}

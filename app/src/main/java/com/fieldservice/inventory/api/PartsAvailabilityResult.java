package com.fieldservice.inventory.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Result of a batch parts availability lookup.
 *
 * <p>The map is keyed by vehicle location ID — the same IDs supplied in
 * {@link PartsAvailabilityQuery#candidateVehicleLocationIds()}. A location absent
 * from the map received no stock rows and should be treated as UNAVAILABLE.
 *
 * <p>The {@code jobVerdict} is the best status across all candidate locations and
 * reachable warehouses combined — useful for the assignment pre-check warning.
 *
 * @param byVehicleLocation  per-candidate vehicle availability result
 * @param jobVerdict         best reachable status across all locations (advisory)
 * @param asOf               timestamp of the stock snapshot used for classification
 * @param stale              true if the result is older than the configured freshness budget
 */
public record PartsAvailabilityResult(
        Map<UUID, CandidateAvailabilityResult> byVehicleLocation,
        PartsAvailabilityStatus jobVerdict,
        Instant asOf,
        boolean stale
) {
    public PartsAvailabilityResult {
        byVehicleLocation = byVehicleLocation == null ? Map.of() : Map.copyOf(byVehicleLocation);
    }

    /** Returns FULLY_STOCKED when there are no required parts (neutral availability). */
    public static PartsAvailabilityResult empty() {
        return new PartsAvailabilityResult(Map.of(), PartsAvailabilityStatus.FULLY_STOCKED,
                Instant.now(), false);
    }
}

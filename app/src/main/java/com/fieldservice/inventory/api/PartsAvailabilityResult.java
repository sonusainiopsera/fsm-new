package com.fieldservice.inventory.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Batch availability result keyed by vehicle location ID.
 *
 * @param byLocationId  per-vehicle-location availability; keyed by vehicle location UUID
 * @param asOf          timestamp when the result was computed
 * @param degraded      true when the result was computed without a cache and Redis was unavailable
 */
public record PartsAvailabilityResult(
        Map<UUID, CandidateAvailability> byLocationId,
        Instant asOf,
        boolean degraded) {

    public PartsAvailabilityResult {
        if (byLocationId == null) byLocationId = Map.of();
        else byLocationId = Map.copyOf(byLocationId);
    }
}

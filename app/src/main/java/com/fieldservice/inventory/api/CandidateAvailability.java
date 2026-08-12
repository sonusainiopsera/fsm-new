package com.fieldservice.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Parts availability assessment for one candidate (vehicle) location.
 *
 * @param locationId  vehicle stock location ID for this candidate
 * @param status      overall classification for this location
 * @param shortfalls  per-part shortfall detail; empty when status is FULLY_STOCKED
 * @param asOf        timestamp when availability was computed (used for freshness checks)
 */
public record CandidateAvailability(
        UUID locationId,
        AvailabilityStatus status,
        List<PartShortfall> shortfalls,
        Instant asOf) {

    public CandidateAvailability {
        if (shortfalls == null) shortfalls = List.of();
        else shortfalls = List.copyOf(shortfalls);
    }
}

package com.fieldservice.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Parts availability verdict for a single stock location (technician vehicle or warehouse).
 *
 * @param locationId stock location ID
 * @param status     classification for this location against the required parts
 * @param shortfalls per-part detail for lines where stock is insufficient;
 *                   empty when {@link PartsAvailabilityStatus#FULLY_STOCKED}
 */
public record CandidateAvailabilityResult(
        UUID locationId,
        PartsAvailabilityStatus status,
        List<PartShortfall> shortfalls
) {
    public CandidateAvailabilityResult {
        shortfalls = shortfalls == null ? List.of() : List.copyOf(shortfalls);
    }
}

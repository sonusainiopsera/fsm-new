package com.fieldservice.geo.api;

import java.util.List;
import java.util.UUID;

/**
 * Result of a batched travel-time matrix request.
 *
 * <p>Every requested origin always has a corresponding entry — the adapter never
 * drops an origin. When the provider is unavailable, individual entries carry
 * Haversine-derived minutes with {@code degraded=true}. The aggregate
 * {@code anyDegraded} flag is {@code true} when at least one entry is degraded.
 */
public record TravelMatrixResult(List<Entry> entries, boolean anyDegraded) {

    public TravelMatrixResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * Per-origin result.
     *
     * @param technicianId     origin identifier (opaque, matches the request)
     * @param estimatedMinutes non-negative drive-time minutes; meaningless when degraded
     * @param degraded         true when this estimate is Haversine-derived, not provider-sourced
     */
    public record Entry(UUID technicianId, int estimatedMinutes, boolean degraded) {

        /** Sentinel degraded entry. */
        public static Entry degraded(UUID technicianId, int haversineMinutes) {
            return new Entry(technicianId, haversineMinutes, true);
        }
    }
}

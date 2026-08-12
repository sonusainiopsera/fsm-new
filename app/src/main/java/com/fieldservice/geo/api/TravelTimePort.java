package com.fieldservice.geo.api;

import java.util.List;
import java.util.UUID;

/**
 * Port for obtaining batched drive-time estimates between technician origins and a
 * work-order destination.
 *
 * <p>Callers in the dispatch module depend only on this interface; all provider,
 * cache, and fallback logic lives behind it in {@code geo.internal}. An ArchUnit
 * rule in the test suite enforces this dependency direction.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>A single call issues at most one outbound HTTP request regardless of origin count.</li>
 *   <li>The result always contains one entry per requested origin — entries are never dropped.</li>
 *   <li>This method never throws; it returns a degraded result on all failure paths.</li>
 * </ul>
 */
public interface TravelTimePort {

    /**
     * Returns batched drive-time estimates from each origin to the destination.
     *
     * @param origins     non-empty list of origin requests; each carries the technician ID
     *                    and the origin coordinates to use for this call
     * @param destination work-order site coordinates
     * @return matrix result — one entry per origin, never null, never throws
     */
    TravelMatrixResult estimate(List<OriginRequest> origins, Coordinates destination);

    /**
     * A single origin in a matrix request.
     *
     * @param technicianId opaque identifier propagated into the result entries
     * @param origin       coordinates to use as the travel origin
     */
    record OriginRequest(UUID technicianId, Coordinates origin) {}
}

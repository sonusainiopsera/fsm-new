package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;

import java.util.Optional;

/**
 * Cache abstraction for travel-time estimates.
 *
 * <p>Implementations must handle Redis unavailability gracefully — returning
 * {@link Optional#empty()} on miss or error rather than throwing.
 */
interface TravelCacheGateway {

    /**
     * Returns the cached minutes for the given origin–destination pair, or empty on miss/error.
     */
    Optional<Double> get(TravelCoordinate origin, TravelCoordinate destination);

    /**
     * Stores the estimated minutes for the given pair.
     * Implementations must not throw — silently discard on error.
     */
    void put(TravelCoordinate origin, TravelCoordinate destination, double estimatedMinutes);
}

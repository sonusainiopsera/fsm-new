package com.fieldservice.inventory.application;

import com.fieldservice.inventory.api.PartsAvailabilityResult;

import java.util.Optional;

/**
 * Cache abstraction for parts availability results.
 *
 * <p>Implementations must handle cache unavailability by returning
 * {@link Optional#empty()} rather than throwing (graceful degradation).
 */
interface AvailabilityCacheGateway {

    /**
     * Retrieves a cached result for the given stable key, or empty on miss or error.
     */
    Optional<PartsAvailabilityResult> get(String cacheKey);

    /**
     * Stores a result. Implementations must not throw on error.
     */
    void put(String cacheKey, PartsAvailabilityResult result);
}

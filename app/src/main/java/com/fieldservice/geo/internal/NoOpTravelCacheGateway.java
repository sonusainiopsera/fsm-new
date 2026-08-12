package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;

import java.util.Optional;

/**
 * No-op cache for the {@code test} profile — always misses so tests hit the
 * adapter directly without needing a Redis instance.
 */
class NoOpTravelCacheGateway implements TravelCacheGateway {

    @Override
    public Optional<Double> get(TravelCoordinate origin, TravelCoordinate destination) {
        return Optional.empty();
    }

    @Override
    public void put(TravelCoordinate origin, TravelCoordinate destination, double estimatedMinutes) {
        // no-op
    }
}

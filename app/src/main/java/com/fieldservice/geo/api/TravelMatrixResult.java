package com.fieldservice.geo.api;

import java.util.List;

/**
 * Batched travel-time result returned by {@link TravelTimePort}.
 *
 * <p>{@code anyDegraded} is {@code true} when at least one entry used the
 * Haversine fallback. Callers may surface this to dispatchers via a degraded
 * indicator on the recommendation.
 */
public record TravelMatrixResult(
        List<TravelMatrixEntry> entries,
        boolean anyDegraded) {

    public static TravelMatrixResult of(List<TravelMatrixEntry> entries) {
        boolean degraded = entries.stream().anyMatch(TravelMatrixEntry::degraded);
        return new TravelMatrixResult(List.copyOf(entries), degraded);
    }
}

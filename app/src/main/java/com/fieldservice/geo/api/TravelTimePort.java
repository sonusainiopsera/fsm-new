package com.fieldservice.geo.api;

import java.util.List;

/**
 * Port for obtaining drive-time estimates between multiple origins and a single destination.
 *
 * <p>Implementations must never throw to the caller — all failure modes are absorbed
 * and returned as degraded entries with {@code travelEstimateDegraded=true}.
 */
public interface TravelTimePort {

    /**
     * Estimates drive-time in minutes from each origin to the destination.
     *
     * <p>Returns one {@link TravelMatrixEntry} per origin in the same order as the
     * input list. If the provider is unavailable, the entry is produced by the
     * Haversine fallback and marked degraded.
     *
     * @param origins     candidate origin coordinates (up to 200)
     * @param destination target work-order location
     * @return matrix result, never null, never throws
     */
    TravelMatrixResult estimateTravelTime(List<TravelCoordinate> origins, TravelCoordinate destination);
}

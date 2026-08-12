package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;

/**
 * Haversine-based drive-time fallback.
 *
 * <p>Converts straight-line distance in kilometres to estimated minutes using a
 * configurable average speed factor. The result is always marked degraded because
 * straight-line distance underestimates actual road distance.
 *
 * <p>The Haversine formula is implemented directly here rather than referencing
 * {@code dispatch.internal.Haversine} to respect module boundaries.
 */
final class HaversineEstimator {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final double avgSpeedKmh;

    HaversineEstimator(double avgSpeedKmh) {
        if (avgSpeedKmh <= 0) {
            throw new IllegalArgumentException("avgSpeedKmh must be positive, got: " + avgSpeedKmh);
        }
        this.avgSpeedKmh = avgSpeedKmh;
    }

    /**
     * Estimates travel time in minutes between two coordinates.
     * Returns 0.0 when origin and destination round to the same cache bucket.
     */
    double estimateMinutes(TravelCoordinate origin, TravelCoordinate destination) {
        double distanceKm = distanceKm(
                origin.latitude(), origin.longitude(),
                destination.latitude(), destination.longitude());
        return (distanceKm / avgSpeedKmh) * 60.0;
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}

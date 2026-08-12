package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.Coordinates;

/**
 * Converts straight-line Haversine distance to drive-time minutes using a
 * configurable average-speed factor.
 *
 * <p>Used as the sole fallback when the external travel-time provider is
 * unavailable. Estimates produced here always set {@code degraded=true} in the
 * result entry so callers can apply an appropriate penalty or neutral score.
 *
 * <h3>Formula</h3>
 * <pre>
 *   minutes = (distanceKm / averageSpeedKph) * 60
 * </pre>
 *
 * Earth radius is fixed at 6371 km (mean). Results are floored at 0.
 */
final class HaversineEstimator {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final double averageSpeedKph;

    HaversineEstimator(double averageSpeedKph) {
        if (averageSpeedKph <= 0)
            throw new IllegalArgumentException("averageSpeedKph must be positive, got: " + averageSpeedKph);
        this.averageSpeedKph = averageSpeedKph;
    }

    /**
     * Estimates drive-time minutes between two coordinates.
     *
     * @return non-negative integer minutes; 0 when origin and destination round to the same point
     */
    int estimateMinutes(Coordinates origin, Coordinates destination) {
        double km = distanceKm(origin.latitude(), origin.longitude(),
                               destination.latitude(), destination.longitude());
        double minutes = (km / averageSpeedKph) * 60.0;
        return (int) Math.max(0, Math.round(minutes));
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

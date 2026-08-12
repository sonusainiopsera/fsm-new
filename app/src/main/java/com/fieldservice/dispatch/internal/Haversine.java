package com.fieldservice.dispatch.internal;

/**
 * Haversine great-circle distance calculator.
 *
 * <p>Pure static utility — no Spring, no JPA, no external dependency.
 * Uses the mean Earth radius of 6,371 km (WGS-84 approximation).
 */
public final class Haversine {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private Haversine() {}

    /**
     * Returns the straight-line Haversine distance in kilometres between two WGS-84 coordinates.
     *
     * @param lat1 latitude of point 1 in decimal degrees
     * @param lon1 longitude of point 1 in decimal degrees
     * @param lat2 latitude of point 2 in decimal degrees
     * @param lon2 longitude of point 2 in decimal degrees
     * @return distance in kilometres ≥ 0
     */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinDLat = Math.sin(dLat / 2);
        double sinDLon = Math.sin(dLon / 2);
        double a = sinDLat * sinDLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * sinDLon * sinDLon;
        return 2.0 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}

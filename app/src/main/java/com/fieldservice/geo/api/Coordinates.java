package com.fieldservice.geo.api;

/**
 * Immutable decimal-degree coordinates.
 *
 * @param latitude  decimal degrees, -90..90
 * @param longitude decimal degrees, -180..180
 */
public record Coordinates(double latitude, double longitude) {

    public Coordinates {
        if (latitude < -90.0 || latitude > 90.0)
            throw new IllegalArgumentException("latitude must be in -90..90, got: " + latitude);
        if (longitude < -180.0 || longitude > 180.0)
            throw new IllegalArgumentException("longitude must be in -180..180, got: " + longitude);
    }
}

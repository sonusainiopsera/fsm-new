package com.fieldservice.geo.api;

/**
 * A geographic coordinate used as input to the travel-time port.
 *
 * <p>Coordinates are not validated here — callers supply meaningful values.
 * The adapter rounds internally before caching (BR-23 data minimisation).
 */
public record TravelCoordinate(double latitude, double longitude) {}

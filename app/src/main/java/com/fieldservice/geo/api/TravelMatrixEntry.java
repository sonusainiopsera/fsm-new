package com.fieldservice.geo.api;

/**
 * Drive-time estimate for a single origin–destination pair.
 *
 * <p>When {@code degraded} is {@code true} the estimate came from the Haversine
 * fallback, not the live provider, and callers should treat it as approximate.
 */
public record TravelMatrixEntry(
        TravelCoordinate origin,
        double estimatedMinutes,
        boolean degraded) {}

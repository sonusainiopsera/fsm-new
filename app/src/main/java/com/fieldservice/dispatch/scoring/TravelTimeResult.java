package com.fieldservice.dispatch.scoring;

import java.util.UUID;

/**
 * Travel-time port result for one technician candidate.
 *
 * <p>When {@code degraded} is true the travel time estimate was unavailable
 * (provider timeout, missing geocoordinates, etc.). Consumers must treat
 * {@code estimatedMinutes} as meaningless in that case and use a neutral
 * contribution instead.
 *
 * @param technicianId     candidate identifier
 * @param estimatedMinutes non-negative estimated travel minutes (undefined when degraded)
 * @param degraded         true when the estimate could not be obtained
 */
public record TravelTimeResult(UUID technicianId, int estimatedMinutes, boolean degraded) {

    /** Sentinel value representing a degraded (unavailable) travel estimate. */
    public static TravelTimeResult degraded(UUID technicianId) {
        return new TravelTimeResult(technicianId, 0, true);
    }
}

package com.fieldservice.dispatch.scoring;

/**
 * Result from the geo travel-time port for one candidate-to-site estimate.
 *
 * @param estimatedMinutes driving/travel time in minutes; ignored when degraded
 * @param degraded         true when the estimate was unavailable or could not be
 *                         computed; scoring uses a neutral contribution in this case
 */
public record TravelTimeEstimate(double estimatedMinutes, boolean degraded) {

    /** Sentinel used when no travel estimate is available at all. */
    public static final TravelTimeEstimate DEGRADED = new TravelTimeEstimate(0, true);

    public TravelTimeEstimate {
        if (!degraded && estimatedMinutes < 0) {
            throw new IllegalArgumentException("estimatedMinutes must be >= 0 when not degraded");
        }
    }
}

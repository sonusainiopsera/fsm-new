package com.fieldservice.workforce.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only port for dispatch to check technician availability.
 * This is the only externally-visible availability query surface;
 * the evaluation logic in {@code workforce.internal.AvailabilityEvaluator} is package-private.
 */
public interface AvailabilityPort {

    /**
     * Returns {@code true} when the technician is available throughout
     * the interval [{@code from}, {@code to}].
     *
     * <p>A technician with no configured availability windows is <em>never</em>
     * reported as available (fail-safe).
     *
     * @param technicianId the technician to check
     * @param from         interval start (inclusive), UTC
     * @param to           interval end (inclusive), UTC
     * @return {@code true} iff the technician has a covering window and no overlapping absence
     */
    boolean isAvailableBetween(UUID technicianId, Instant from, Instant to);
}

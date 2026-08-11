package com.fieldservice.workforce.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public availability-check port for the workforce module, consumed by dispatch.
 *
 * <p>Implementations load windows and absences from the database then delegate to
 * {@link com.fieldservice.workforce.application.AvailabilityEvaluator}.
 */
public interface AvailabilityPort {

    /**
     * Returns {@code true} when the technician has a working window covering the entire
     * {@code [from, to)} interval and no absence overlaps it.
     *
     * <p>Returns {@code false} when the technician has no windows (fail-safe).
     */
    boolean isAvailableBetween(UUID technicianId, Instant from, Instant to);
}

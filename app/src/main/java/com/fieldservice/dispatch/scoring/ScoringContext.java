package com.fieldservice.dispatch.scoring;

import java.util.List;

/**
 * Work-order and team-wide context passed to every factor during a scoring pass.
 *
 * @param requiredCertificationCodes  certifications the work order requires (may be empty)
 * @param workOrderJobType            fault category / job type string for experience matching
 *                                    (null means no job-type preference)
 * @param teamMeanBookedHours         arithmetic mean of bookedHours across all candidates
 *                                    in the eligible set; 0.0 when the set is empty or all zero
 * @param travelHorizonMinutes        normalisation ceiling for travel efficiency: a technician
 *                                    at this distance scores 0.0; loaded from configuration
 */
public record ScoringContext(
        List<String> requiredCertificationCodes,
        String workOrderJobType,
        double teamMeanBookedHours,
        int travelHorizonMinutes) {

    public ScoringContext {
        if (requiredCertificationCodes == null) requiredCertificationCodes = List.of();
        else requiredCertificationCodes = List.copyOf(requiredCertificationCodes);
        if (travelHorizonMinutes <= 0)
            throw new IllegalArgumentException("travelHorizonMinutes must be positive");
    }
}

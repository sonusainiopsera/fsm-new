package com.fieldservice.dispatch.scoring;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-candidate input to the {@link ScoringEngine}.
 *
 * <p>Immutable snapshot assembled by the caller before invoking the engine;
 * no I/O occurs inside the engine itself.
 *
 * @param technicianId              identifier of the eligible technician
 * @param heldCertificationCodes    active certification type codes the technician holds
 * @param requiredCertificationCodes certification codes required by the work order
 * @param priorJobTypeExperienceCount number of prior completed jobs of this job-type
 * @param travelTime                estimate from the geo travel-time port (may be degraded)
 * @param bookedHours               technician's current scheduled workload in hours
 * @param teamMeanBookedHours       mean booked hours across the eligible candidate pool
 * @param requiredPartsAvailable    advisory: whether all required parts are in stock
 */
public record ScoringContext(
        UUID technicianId,
        List<String> heldCertificationCodes,
        Set<String> requiredCertificationCodes,
        int priorJobTypeExperienceCount,
        TravelTimeEstimate travelTime,
        double bookedHours,
        double teamMeanBookedHours,
        boolean requiredPartsAvailable
) {
    public ScoringContext {
        heldCertificationCodes    = heldCertificationCodes    == null ? List.of() : List.copyOf(heldCertificationCodes);
        requiredCertificationCodes = requiredCertificationCodes == null ? Set.of() : Set.copyOf(requiredCertificationCodes);
        if (travelTime == null) travelTime = TravelTimeEstimate.DEGRADED;
        if (bookedHours < 0) bookedHours = 0;
        if (teamMeanBookedHours < 0) teamMeanBookedHours = 0;
        if (priorJobTypeExperienceCount < 0) priorJobTypeExperienceCount = 0;
    }
}

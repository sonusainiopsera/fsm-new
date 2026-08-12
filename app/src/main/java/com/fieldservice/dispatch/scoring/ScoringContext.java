package com.fieldservice.dispatch.scoring;

import com.fieldservice.inventory.api.PartsAvailabilityStatus;

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
 * @param partsAvailabilityStatus   advisory parts availability verdict for this candidate
 *                                  ({@code null} treated as FULLY_STOCKED — no required parts)
 * @param partsAvailabilityRatio    fraction of required part quantities satisfied from the
 *                                  candidate's van stock (0..1); 1.0 when no parts required
 */
public record ScoringContext(
        UUID technicianId,
        List<String> heldCertificationCodes,
        Set<String> requiredCertificationCodes,
        int priorJobTypeExperienceCount,
        TravelTimeEstimate travelTime,
        double bookedHours,
        double teamMeanBookedHours,
        PartsAvailabilityStatus partsAvailabilityStatus,
        double partsAvailabilityRatio
) {
    public ScoringContext {
        heldCertificationCodes     = heldCertificationCodes    == null ? List.of() : List.copyOf(heldCertificationCodes);
        requiredCertificationCodes = requiredCertificationCodes == null ? Set.of() : Set.copyOf(requiredCertificationCodes);
        if (travelTime == null) travelTime = TravelTimeEstimate.DEGRADED;
        if (bookedHours < 0) bookedHours = 0;
        if (teamMeanBookedHours < 0) teamMeanBookedHours = 0;
        if (priorJobTypeExperienceCount < 0) priorJobTypeExperienceCount = 0;
        if (partsAvailabilityStatus == null) partsAvailabilityStatus = PartsAvailabilityStatus.FULLY_STOCKED;
        if (partsAvailabilityRatio < 0) partsAvailabilityRatio = 0;
        if (partsAvailabilityRatio > 1) partsAvailabilityRatio = 1;
    }
}

package com.fieldservice.dispatch.scoring;

import com.fieldservice.inventory.api.CandidateAvailability;

import java.util.List;
import java.util.UUID;

/**
 * Immutable per-candidate snapshot consumed by all factor implementations.
 *
 * @param technicianId           candidate identifier
 * @param certificationCodes     certification type codes the technician currently holds
 * @param priorJobTypeExperience count of prior work orders matching the work order's
 *                               fault category / job type (non-negative)
 * @param bookedHours            total hours already booked for this technician today
 *                               (non-negative; 0 = fully available)
 * @param travelTime             travel-time port result for this candidate
 * @param partsAvailabilityScore fraction of required parts available at this technician's
 *                               home depot in [0.0, 1.0]; 1.0 when no parts are required
 * @param candidateAvailability  rich parts availability detail; null when no parts are required
 *                               or when the availability lookup was skipped
 */
public record CandidateScoringData(
        UUID technicianId,
        List<String> certificationCodes,
        int priorJobTypeExperience,
        double bookedHours,
        TravelTimeResult travelTime,
        double partsAvailabilityScore,
        CandidateAvailability candidateAvailability) {

    public CandidateScoringData {
        if (certificationCodes == null) certificationCodes = List.of();
        else certificationCodes = List.copyOf(certificationCodes);
        if (bookedHours < 0) throw new IllegalArgumentException("bookedHours must be >= 0");
        if (partsAvailabilityScore < 0.0 || partsAvailabilityScore > 1.0)
            throw new IllegalArgumentException("partsAvailabilityScore must be in [0,1]");
        if (travelTime == null) travelTime = TravelTimeResult.degraded(technicianId);
    }
}

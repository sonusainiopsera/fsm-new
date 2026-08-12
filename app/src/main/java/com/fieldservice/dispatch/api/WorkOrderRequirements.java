package com.fieldservice.dispatch.api;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of what a work order requires from a candidate technician.
 *
 * @param requiredCertificationCodes certification type codes the technician must hold
 *                                   (may be empty — all otherwise-eligible technicians pass)
 * @param serviceWindowStart         inclusive start of the required service window
 * @param serviceWindowEnd           inclusive end of the required service window
 * @param siteLatitude               work order site latitude in decimal degrees (nullable —
 *                                   reach filter is skipped when null)
 * @param siteLongitude              work order site longitude in decimal degrees (nullable —
 *                                   reach filter is skipped when null)
 * @param maxReachKm                 maximum straight-line Haversine distance in km;
 *                                   must be positive; ignored when site coordinates are null
 */
public record WorkOrderRequirements(
        List<String> requiredCertificationCodes,
        Instant serviceWindowStart,
        Instant serviceWindowEnd,
        Double siteLatitude,
        Double siteLongitude,
        double maxReachKm
) {
    public WorkOrderRequirements {
        if (requiredCertificationCodes == null) {
            requiredCertificationCodes = List.of();
        }
        if (serviceWindowStart == null) throw new IllegalArgumentException("serviceWindowStart must not be null");
        if (serviceWindowEnd == null)   throw new IllegalArgumentException("serviceWindowEnd must not be null");
        if (maxReachKm <= 0)            throw new IllegalArgumentException("maxReachKm must be positive");
        requiredCertificationCodes = List.copyOf(requiredCertificationCodes);
    }
}

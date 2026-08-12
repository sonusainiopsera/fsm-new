package com.fieldservice.dispatch.api;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable value object describing the dispatch eligibility constraints for one work order.
 *
 * @param requiredCertificationCodes  codes from {@code certification_type.code};
 *                                    empty set = no certification requirement
 * @param serviceWindowStart          inclusive start of the required service interval
 * @param serviceWindowEnd            exclusive end of the required service interval
 * @param siteLatitude                WGS-84 latitude of the work order site;
 *                                    {@code null} = skip geographic reach check
 * @param siteLongitude               WGS-84 longitude; {@code null} = skip geographic reach check
 * @param maxReachKm                  straight-line Haversine distance ceiling in kilometres
 */
public record WorkOrderRequirements(
        Set<String> requiredCertificationCodes,
        Instant serviceWindowStart,
        Instant serviceWindowEnd,
        Double siteLatitude,
        Double siteLongitude,
        double maxReachKm
) {
    public WorkOrderRequirements {
        requiredCertificationCodes = requiredCertificationCodes == null
                ? Set.of()
                : Set.copyOf(requiredCertificationCodes);
        if (serviceWindowStart != null && serviceWindowEnd != null
                && !serviceWindowEnd.isAfter(serviceWindowStart)) {
            throw new IllegalArgumentException(
                    "serviceWindowEnd must be after serviceWindowStart");
        }
        if (maxReachKm <= 0) {
            throw new IllegalArgumentException("maxReachKm must be positive");
        }
    }
}

package com.fieldservice.dispatch.web.dto;

import com.fieldservice.inventory.api.PartShortfall;

import java.time.Instant;
import java.util.List;

/**
 * Per-factor scoring breakdown in a recommendation response.
 *
 * <p>The {@code partsStatus}, {@code shortfalls}, {@code asOf}, and {@code stale} fields
 * are populated only for the {@code PARTS_AVAILABILITY} factor entry; they are null for
 * all other factors.
 */
public record FactorDto(
        String            factorCode,
        double            rawValue,
        double            normalisedValue,
        double            weight,
        double            weightedContribution,
        String            explanation,
        boolean           degraded,
        String            partsStatus,
        List<PartShortfall> shortfalls,
        Instant           asOf,
        boolean           stale) {

    /** Convenience constructor for non-PARTS_AVAILABILITY factors. */
    public FactorDto(String factorCode, double rawValue, double normalisedValue,
                     double weight, double weightedContribution,
                     String explanation, boolean degraded) {
        this(factorCode, rawValue, normalisedValue, weight, weightedContribution,
             explanation, degraded, null, null, null, false);
    }
}

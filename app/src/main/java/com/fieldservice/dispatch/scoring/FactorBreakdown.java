package com.fieldservice.dispatch.scoring;

import com.fieldservice.inventory.api.CandidateAvailability;

/**
 * Immutable per-factor result for a single candidate.
 *
 * @param factorCode           stable code matching the weight table row
 * @param rawValue             raw input value before normalisation (unit depends on factor)
 * @param normalisedValue      value in [0.0, 1.0] after normalisation
 * @param weight               active weight loaded from configuration
 * @param weightedContribution {@code normalisedValue * weight}; summed by the engine
 * @param explanation          human-readable sentence stating what drove this score
 * @param degraded             true when the factor used a fallback value because input data
 *                             was unavailable (e.g. travel estimate timed out)
 * @param partsAvailability    rich parts availability detail; non-null only for
 *                             PARTS_AVAILABILITY factor entries
 */
public record FactorBreakdown(
        String              factorCode,
        double              rawValue,
        double              normalisedValue,
        double              weight,
        double              weightedContribution,
        String              explanation,
        boolean             degraded,
        CandidateAvailability partsAvailability) {

    /** Convenience constructor for factors that carry no parts availability data. */
    public FactorBreakdown(String factorCode, double rawValue, double normalisedValue,
                           double weight, double weightedContribution,
                           String explanation, boolean degraded) {
        this(factorCode, rawValue, normalisedValue, weight, weightedContribution,
             explanation, degraded, null);
    }

    public FactorBreakdown {
        if (normalisedValue < 0.0 || normalisedValue > 1.0) {
            throw new IllegalArgumentException(
                    "normalisedValue must be in [0,1] for factor " + factorCode
                    + " but was " + normalisedValue);
        }
    }
}

package com.fieldservice.dispatch.scoring;

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
 */
public record FactorBreakdown(
        String  factorCode,
        double  rawValue,
        double  normalisedValue,
        double  weight,
        double  weightedContribution,
        String  explanation,
        boolean degraded) {

    public FactorBreakdown {
        if (normalisedValue < 0.0 || normalisedValue > 1.0) {
            throw new IllegalArgumentException(
                    "normalisedValue must be in [0,1] for factor " + factorCode
                    + " but was " + normalisedValue);
        }
    }
}

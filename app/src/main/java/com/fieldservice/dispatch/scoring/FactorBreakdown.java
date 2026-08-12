package com.fieldservice.dispatch.scoring;

/**
 * Result of one scoring factor's evaluation for a single candidate.
 *
 * @param factorCode          matches {@link ScoringFactor#factorCode()}
 * @param rawValue            un-transformed domain value (e.g. minutes, booked hours ratio)
 * @param normalisedValue     value in the closed interval [0, 1] after the factor's transform
 * @param weight              the configured weight for this factor
 * @param weightedContribution {@code weight * normalisedValue} — addend in the composite sum
 * @param explanation         human-readable string describing this specific outcome
 * @param degraded            true when the factor could not fully compute (e.g. missing travel
 *                            data) and contributed a neutral value instead
 */
public record FactorBreakdown(
        String factorCode,
        double rawValue,
        double normalisedValue,
        double weight,
        double weightedContribution,
        String explanation,
        boolean degraded
) {
    public FactorBreakdown {
        normalisedValue = clamp(normalisedValue);
        weightedContribution = weight * normalisedValue;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

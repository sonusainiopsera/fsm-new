package com.fieldservice.dispatch.scoring;

/**
 * Strategy interface for a single scoring dimension.
 *
 * <p>Implementations are pure functions: no side effects, no I/O, deterministic.
 * Each implementation is instantiated once and reused across all scoring calls.
 *
 * <p>The normalised value returned in {@link FactorBreakdown#normalisedValue()} must
 * lie in the closed interval [0, 1]. The {@link FactorBreakdown} constructor enforces
 * this by clamping at construction time.
 */
public interface ScoringFactor {

    /**
     * Unique code matching the {@code factor_code} column in {@code dispatch_scoring_weight}.
     */
    String factorCode();

    /**
     * Computes the factor breakdown for one candidate.
     *
     * <p>Implementations retrieve their configured weight via
     * {@link ScoringWeights#weightFor(String)} using their own {@link #factorCode()}.
     * Passing the full weights snapshot also allows configuration-dependent factors
     * (e.g. WorkloadFairnessFactor) to read the penalty exponent.
     *
     * @param context candidate-specific scoring inputs
     * @param weights current weight snapshot; never null
     * @return breakdown with raw value, normalised value, weight, weighted contribution,
     *         and a human-readable explanation
     */
    FactorBreakdown normalise(ScoringContext context, ScoringWeights weights);
}

package com.fieldservice.dispatch.scoring;

/**
 * Strategy interface for a single scoring dimension.
 *
 * <p>Implementations must be stateless and free of Spring annotations —
 * wiring happens in {@link ScoringConfiguration}.
 *
 * <p>Every factor maps raw input values to a normalised score in the closed
 * interval [0.0, 1.0] and returns a human-readable explanation string so
 * dispatchers can see why a candidate was ranked as it was.
 */
public interface ScoringFactor {

    /** Stable machine-readable code used in weight table lookups. */
    String factorCode();

    /**
     * Normalises the relevant dimension for one candidate and returns
     * a full breakdown including raw value, normalised value, and explanation.
     *
     * @param data    per-candidate scoring data snapshot
     * @param context work-order and team-wide context for this scoring pass
     * @return breakdown with normalised value in [0, 1]
     */
    FactorBreakdown normalise(CandidateScoringData data, ScoringContext context);
}

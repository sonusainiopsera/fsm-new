package com.fieldservice.dispatch.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Framework-free, deterministic scoring engine.
 *
 * <p>Accepts an eligible candidate set and a scoring context, applies each active
 * factor, and returns candidates ordered by descending composite score with
 * ascending technician-identifier tie-break for pagination stability.
 *
 * <h3>Composite formula</h3>
 * <pre>
 *   compositeScore = Σ(weight_i * normalised_i) / Σ(weight_i)
 * </pre>
 *
 * Dividing by the total active weight ensures scores always fall in [0,1] and that
 * zeroing or removing a factor cannot skew the scale.  When all weights are zero the
 * engine returns candidates in identifier order with a neutral score of 0.0.
 *
 * <h3>No Spring annotations</h3>
 * This class has no Spring annotations. Wiring is handled by {@link ScoringConfiguration}.
 */
public final class ScoringEngine {

    private final List<ScoringFactor> factors;

    public ScoringEngine(List<ScoringFactor> factors) {
        if (factors == null || factors.isEmpty())
            throw new IllegalArgumentException("At least one ScoringFactor is required");
        this.factors = List.copyOf(factors);
    }

    /**
     * Scores and orders the given eligible candidates.
     *
     * @param candidates list of per-candidate data snapshots (eligible set only)
     * @param context    work-order and team context for this pass
     * @param weights    active weights and configuration loaded from the database
     * @return candidates ordered by descending composite score, ascending id on tie
     */
    public List<ScoredCandidate> score(List<CandidateScoringData> candidates,
                                       ScoringContext context,
                                       ScoringWeights weights) {
        List<ScoredCandidate> results = new ArrayList<>(candidates.size());

        for (CandidateScoringData data : candidates) {
            results.add(scoreOne(data, context, weights));
        }

        // AC-1: descending composite score, tie-broken ascending on technician identifier
        results.sort(Comparator
                .comparingDouble(ScoredCandidate::compositeScore).reversed()
                .thenComparing(ScoredCandidate::technicianId));

        return results;
    }

    private ScoredCandidate scoreOne(CandidateScoringData data,
                                     ScoringContext context,
                                     ScoringWeights weights) {
        List<FactorBreakdown> breakdowns = new ArrayList<>(factors.size());
        boolean anyDegraded = false;

        for (ScoringFactor factor : factors) {
            double weight = weights.weightFor(factor.factorCode());
            // Compute raw breakdown (weight and contribution temporarily 0)
            FactorBreakdown raw = factor.normalise(data, context);
            double contribution = weight * raw.normalisedValue();

            // Rebuild with actual weight and contribution
            FactorBreakdown bd = new FactorBreakdown(
                    raw.factorCode(),
                    raw.rawValue(),
                    raw.normalisedValue(),
                    weight,
                    contribution,
                    raw.explanation(),
                    raw.degraded());

            breakdowns.add(bd);
            if (raw.degraded()) anyDegraded = true;
        }

        double totalWeight = weights.totalWeight();
        double composite;
        if (totalWeight == 0.0) {
            composite = 0.0; // all weights zero — neutral score, identifier order applies
        } else {
            double weightedSum = breakdowns.stream()
                    .mapToDouble(FactorBreakdown::weightedContribution)
                    .sum();
            composite = Math.max(0.0, Math.min(1.0, weightedSum / totalWeight));
        }

        return new ScoredCandidate(data.technicianId(), composite, breakdowns, anyDegraded);
    }
}

package com.fieldservice.dispatch.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-free, deterministic scoring engine.
 *
 * <p>Wiring happens in {@link ScoringConfiguration}; this class has no Spring annotations.
 *
 * <p>Ordering contract (AC-1):
 * <pre>
 *   Comparator.comparingDouble(ScoredCandidate::compositeScore).reversed()
 *       .thenComparing(ScoredCandidate::technicianId)
 * </pre>
 * The secondary tie-break on {@code technicianId} ensures stable pagination even
 * when callers add or remove candidates between requests.
 *
 * <p>Composite formula (AC-6):
 * <pre>
 *   composite = sum(weight_i * normalised_i) / sum(active weight_i)
 * </pre>
 * When all active weights are zero the composite defaults to 0.0 and candidates are
 * ordered by identifier only.
 */
public class ScoringEngine {

    private static final Comparator<ScoredCandidate> ORDER =
            Comparator.comparingDouble(ScoredCandidate::compositeScore).reversed()
                    .thenComparing(ScoredCandidate::technicianId);

    private final List<ScoringFactor> factors;

    public ScoringEngine(List<ScoringFactor> factors) {
        this.factors = List.copyOf(factors);
    }

    /**
     * Scores and ranks the given candidates.
     *
     * @param candidates list of eligible candidates (engine never re-introduces excluded ones)
     * @param weights    current weight snapshot, loaded once per request
     * @return immutable ordered list, descending composite score, ascending id tie-break
     */
    public List<ScoredCandidate> rank(List<ScoringContext> candidates, ScoringWeights weights) {
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (ScoringContext ctx : candidates) {
            scored.add(score(ctx, weights));
        }
        scored.sort(ORDER);
        return List.copyOf(scored);
    }

    private ScoredCandidate score(ScoringContext ctx, ScoringWeights weights) {
        List<FactorBreakdown> breakdowns = new ArrayList<>(factors.size());
        double weightedSum    = 0;
        double activeWeightSum = 0;
        boolean degraded = false;

        for (ScoringFactor factor : factors) {
            String code = factor.factorCode();
            if (!weights.isActive(code)) {
                continue;
            }
            FactorBreakdown bd = factor.normalise(ctx, weights);
            breakdowns.add(bd);
            weightedSum    += bd.weightedContribution();
            activeWeightSum += weights.weightFor(code);
            if (bd.degraded()) {
                degraded = true;
            }
        }

        double composite = activeWeightSum > 0 ? weightedSum / activeWeightSum : 0.0;
        composite = Math.max(0.0, Math.min(1.0, composite));

        return new ScoredCandidate(ctx.technicianId(), composite, degraded, breakdowns);
    }
}

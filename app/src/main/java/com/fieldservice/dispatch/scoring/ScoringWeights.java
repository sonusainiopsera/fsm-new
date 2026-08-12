package com.fieldservice.dispatch.scoring;

import java.util.Map;

/**
 * Immutable snapshot of active scoring weights and the workload exponent,
 * loaded from {@code dispatch_scoring_weight} and {@code dispatch_scoring_config}.
 *
 * @param weights          factor-code → weight; contains only active (weight > 0) entries
 * @param workloadExponent exponent for the super-linear workload fairness penalty;
 *                         must be strictly greater than 1.0
 * @param travelHorizonMinutes normalisation ceiling for travel efficiency
 */
public record ScoringWeights(
        Map<String, Double> weights,
        double workloadExponent,
        int travelHorizonMinutes) {

    public ScoringWeights {
        if (weights == null) throw new IllegalArgumentException("weights must not be null");
        weights = Map.copyOf(weights);
        if (workloadExponent <= 1.0)
            throw new IllegalArgumentException(
                    "workloadExponent must be > 1.0 but was " + workloadExponent);
        if (travelHorizonMinutes <= 0)
            throw new IllegalArgumentException("travelHorizonMinutes must be positive");
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (e.getValue() < 0)
                throw new IllegalArgumentException(
                        "Weight for " + e.getKey() + " is negative: " + e.getValue());
        }
    }

    /** Returns the weight for the given factor code, or 0.0 if absent/inactive. */
    public double weightFor(String factorCode) {
        return weights.getOrDefault(factorCode, 0.0);
    }

    /** True when there is at least one active factor with positive weight. */
    public boolean hasActiveFactors() {
        return weights.values().stream().anyMatch(w -> w > 0);
    }

    /** Sum of all active weights (used for normalisation). */
    public double totalWeight() {
        return weights.values().stream().mapToDouble(Double::doubleValue).sum();
    }
}

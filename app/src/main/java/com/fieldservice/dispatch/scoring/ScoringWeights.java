package com.fieldservice.dispatch.scoring;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of all configured scoring weights, loaded once per request
 * from the in-process cache in {@link ScoringWeightsLoader}.
 *
 * @param entries                list of per-factor weight entries from the database
 * @param workloadPenaltyExponent super-linear penalty exponent; must be &gt; 1
 */
public record ScoringWeights(
        List<WeightEntry> entries,
        double workloadPenaltyExponent
) {
    /** One row from dispatch_scoring_weight. */
    public record WeightEntry(String factorCode, double weight, boolean active) {}

    public ScoringWeights {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (workloadPenaltyExponent <= 1.0) {
            throw new IllegalArgumentException(
                    "workloadPenaltyExponent must be > 1; got " + workloadPenaltyExponent);
        }
    }

    /** Returns the configured weight for a factor, or 0.0 if not present. */
    public double weightFor(String factorCode) {
        for (WeightEntry e : entries) {
            if (e.factorCode().equals(factorCode)) return e.weight();
        }
        return 0.0;
    }

    /** Returns true when the factor is present and marked active. */
    public boolean isActive(String factorCode) {
        for (WeightEntry e : entries) {
            if (e.factorCode().equals(factorCode)) return e.active();
        }
        return false;
    }

    /** Sum of weights for all active factors. */
    public double activeWeightSum() {
        double sum = 0;
        for (WeightEntry e : entries) {
            if (e.active()) sum += e.weight();
        }
        return sum;
    }
}

package com.fieldservice.dispatch.scoring.factors;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringFactor;
import com.fieldservice.dispatch.scoring.ScoringWeights;

/**
 * Workload fairness factor.
 *
 * <p>Applies a super-linear penalty when a technician is over-loaded relative to
 * the team mean, nudging routine work toward under-loaded technicians without
 * destroying travel efficiency.
 *
 * <p>Penalty formula (AC-4):
 * <pre>
 *   relativeOverload = max(0, (bookedHours - teamMean) / teamMean)
 *   penalty          = pow(relativeOverload, exponent)
 *   normalised       = max(0, 1 - penalty)
 * </pre>
 * The {@code exponent} is read from {@code ScoringWeights#workloadPenaltyExponent()}
 * (seeded as 2.0 by V52 migration) and must be &gt; 1 (enforced by {@link ScoringWeights}).
 *
 * <p>Zero-mean guard: when {@code teamMeanBookedHours == 0} all technicians score 1.0.
 *
 * <p>Raw value: the candidate's booked hours.
 */
public class WorkloadFairnessFactor implements ScoringFactor {

    public static final String CODE = "WORKLOAD_FAIRNESS";

    @Override
    public String factorCode() {
        return CODE;
    }

    @Override
    public FactorBreakdown normalise(ScoringContext ctx, ScoringWeights weights) {
        double w        = weights.weightFor(CODE);
        double exponent = weights.workloadPenaltyExponent();
        double teamMean = ctx.teamMeanBookedHours();
        double booked   = ctx.bookedHours();

        if (teamMean <= 0.0) {
            return new FactorBreakdown(CODE, booked, 1.0, w, w,
                    String.format("team mean=0 h — neutral contribution (booked=%.1f h)", booked),
                    false);
        }

        double relativeOverload = Math.max(0.0, (booked - teamMean) / teamMean);
        double penalty          = Math.pow(relativeOverload, exponent);
        double normalised       = Math.max(0.0, 1.0 - penalty);

        String explanation = String.format(
                "booked=%.1f h, mean=%.1f h, overload=%.0f%%, penalty(exp=%.1f)=%.3f → %.0f%%",
                booked, teamMean, relativeOverload * 100, exponent, penalty, normalised * 100);

        return new FactorBreakdown(CODE, booked, normalised, w,
                w * normalised, explanation, false);
    }
}

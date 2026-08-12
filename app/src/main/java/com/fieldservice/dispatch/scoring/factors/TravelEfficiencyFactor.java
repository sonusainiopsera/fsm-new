package com.fieldservice.dispatch.scoring.factors;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringFactor;
import com.fieldservice.dispatch.scoring.ScoringWeights;

/**
 * Travel efficiency factor.
 *
 * <p>Normalisation: inverse-linear against a configurable horizon ({@value #HORIZON_MINUTES} min).
 * <pre>
 *   normalised = max(0, 1 - estimatedMinutes / HORIZON_MINUTES)
 * </pre>
 * On-site (0 min) scores 1.0; at the horizon scores 0.0; beyond the horizon is clamped to 0.
 *
 * <p>Degraded input: when the travel-time estimate is flagged as degraded, the factor
 * contributes a neutral value of {@value #DEGRADED_NEUTRAL} and marks the breakdown as degraded,
 * rather than dropping the candidate from ranking.
 *
 * <p>Raw value: estimated travel minutes, or 0.0 when degraded.
 */
public class TravelEfficiencyFactor implements ScoringFactor {

    public static final String CODE = "TRAVEL_EFFICIENCY";
    static final double HORIZON_MINUTES = 120.0;
    static final double DEGRADED_NEUTRAL = 0.5;

    @Override
    public String factorCode() {
        return CODE;
    }

    @Override
    public FactorBreakdown normalise(ScoringContext ctx, ScoringWeights weights) {
        double w = weights.weightFor(CODE);

        if (ctx.travelTime().degraded()) {
            return new FactorBreakdown(CODE, 0.0, DEGRADED_NEUTRAL, w,
                    w * DEGRADED_NEUTRAL,
                    "travel estimate unavailable — neutral contribution (0.5) applied",
                    true);
        }

        double minutes    = ctx.travelTime().estimatedMinutes();
        double normalised = Math.max(0.0, 1.0 - minutes / HORIZON_MINUTES);
        String explanation = String.format(
                "%.1f min travel → %.0f%% (horizon=%d min)",
                minutes, normalised * 100, (int) HORIZON_MINUTES);

        return new FactorBreakdown(CODE, minutes, normalised, w,
                w * normalised, explanation, false);
    }
}

package com.fieldservice.dispatch.scoring.factors;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringFactor;
import com.fieldservice.dispatch.scoring.ScoringWeights;

/**
 * Parts availability factor — advisory soft contribution only.
 *
 * <p>This factor must never zero out or gate a candidate (AC constraint).
 * It provides a soft upward nudge when parts are in stock:
 * <ul>
 *   <li>Parts available: normalised = 1.0</li>
 *   <li>Parts unavailable: normalised = 0.5 (never 0, preserving eligibility)</li>
 * </ul>
 *
 * <p>Raw value: 1.0 if available, 0.0 if not.
 */
public class PartsAvailabilityFactor implements ScoringFactor {

    public static final String CODE = "PARTS_AVAILABILITY";
    static final double UNAVAILABLE_SCORE = 0.5;

    @Override
    public String factorCode() {
        return CODE;
    }

    @Override
    public FactorBreakdown normalise(ScoringContext ctx, ScoringWeights weights) {
        double w = weights.weightFor(CODE);

        if (ctx.requiredPartsAvailable()) {
            return new FactorBreakdown(CODE, 1.0, 1.0, w, w,
                    "required parts in stock", false);
        }
        return new FactorBreakdown(CODE, 0.0, UNAVAILABLE_SCORE, w,
                w * UNAVAILABLE_SCORE,
                "required parts not confirmed in stock — advisory penalty",
                false);
    }
}

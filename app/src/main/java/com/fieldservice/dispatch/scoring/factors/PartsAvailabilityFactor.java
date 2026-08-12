package com.fieldservice.dispatch.scoring.factors;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringFactor;
import com.fieldservice.dispatch.scoring.ScoringWeights;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;

/**
 * Parts availability factor — advisory soft contribution only.
 *
 * <p>This factor must never zero out or gate a candidate (AC-8 constraint).
 * It contributes a soft upward nudge when parts are in stock:
 * <ul>
 *   <li>FULLY_STOCKED: normalised = 1.0 — all required parts in vehicle</li>
 *   <li>PARTIALLY_STOCKED: normalised = 0.7 — some parts in vehicle, some shortfalls</li>
 *   <li>COLLECTABLE: normalised = 0.6 — all shortfalls covered by reachable warehouses</li>
 *   <li>UNAVAILABLE / null: normalised = {@link #UNAVAILABLE_SCORE} (0.5) — never 0.0</li>
 * </ul>
 *
 * <p>Raw value: the normalised value before weight application.
 */
public class PartsAvailabilityFactor implements ScoringFactor {

    public static final String CODE = "PARTS_AVAILABILITY";
    public static final double UNAVAILABLE_SCORE = 0.5;

    private static final double PARTIALLY_STOCKED_SCORE = 0.7;
    private static final double COLLECTABLE_SCORE        = 0.6;

    @Override
    public String factorCode() {
        return CODE;
    }

    @Override
    public FactorBreakdown normalise(ScoringContext ctx, ScoringWeights weights) {
        double w = weights.weightFor(CODE);
        PartsAvailabilityStatus status = ctx.partsAvailabilityStatus();

        return switch (status) {
            case FULLY_STOCKED -> new FactorBreakdown(CODE, 1.0, 1.0, w, w,
                    "all required parts fully stocked on vehicle", false);

            case PARTIALLY_STOCKED -> new FactorBreakdown(CODE, PARTIALLY_STOCKED_SCORE,
                    PARTIALLY_STOCKED_SCORE, w, w * PARTIALLY_STOCKED_SCORE,
                    "some required parts in vehicle stock — partial shortfall", false);

            case COLLECTABLE -> new FactorBreakdown(CODE, COLLECTABLE_SCORE,
                    COLLECTABLE_SCORE, w, w * COLLECTABLE_SCORE,
                    "required parts not on vehicle but collectable from reachable warehouse", false);

            case UNAVAILABLE -> new FactorBreakdown(CODE, UNAVAILABLE_SCORE,
                    UNAVAILABLE_SCORE, w, w * UNAVAILABLE_SCORE,
                    "required parts not confirmed in stock — advisory penalty only", false);
        };
    }
}

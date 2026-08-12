package com.fieldservice.dispatch.scoring.factors;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringFactor;
import com.fieldservice.dispatch.scoring.ScoringWeights;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;

/**
 * Parts availability factor — advisory soft contribution only.
 *
 * <p>Computes {@code satisfiedQuantity / requiredQuantity} as the primary signal,
 * applying a configurable collectable discount when shortfalls are satisfiable from
 * a nearby stocking location (AC-1).
 *
 * <p>This factor must never zero out or gate a candidate (AC-2):
 * <ul>
 *   <li>FULLY_STOCKED: normalised = 1.0</li>
 *   <li>PARTIALLY_STOCKED: normalised = max(vanRatio, {@link #NEUTRAL_FLOOR})</li>
 *   <li>COLLECTABLE: normalised = vanRatio + nearbyDiscount * (1 - vanRatio)</li>
 *   <li>UNAVAILABLE: normalised = {@link #NEUTRAL_FLOOR} (0.5) — never 0.0</li>
 * </ul>
 */
public class PartsAvailabilityFactor implements ScoringFactor {

    public static final String CODE = "PARTS_AVAILABILITY";
    public static final double NEUTRAL_FLOOR = 0.5;
    public static final double DEFAULT_NEARBY_COLLECTABLE_DISCOUNT = 0.6;

    private final double nearbyCollectableDiscount;

    public PartsAvailabilityFactor() {
        this(DEFAULT_NEARBY_COLLECTABLE_DISCOUNT);
    }

    public PartsAvailabilityFactor(double nearbyCollectableDiscount) {
        if (nearbyCollectableDiscount < 0 || nearbyCollectableDiscount > 1) {
            throw new IllegalArgumentException(
                    "nearbyCollectableDiscount must be in [0, 1], got: " + nearbyCollectableDiscount);
        }
        this.nearbyCollectableDiscount = nearbyCollectableDiscount;
    }

    @Override
    public String factorCode() {
        return CODE;
    }

    @Override
    public FactorBreakdown normalise(ScoringContext ctx, ScoringWeights weights) {
        double w = weights.weightFor(CODE);
        PartsAvailabilityStatus status = ctx.partsAvailabilityStatus();
        double ratio = ctx.partsAvailabilityRatio();

        double normalisedValue = switch (status) {
            case FULLY_STOCKED ->
                    1.0;
            case PARTIALLY_STOCKED ->
                    Math.max(ratio, NEUTRAL_FLOOR);
            case COLLECTABLE ->
                    ratio + nearbyCollectableDiscount * (1.0 - ratio);
            case UNAVAILABLE ->
                    NEUTRAL_FLOOR;
        };

        String explanation = switch (status) {
            case FULLY_STOCKED ->
                    "all required parts fully stocked on vehicle";
            case PARTIALLY_STOCKED ->
                    String.format("%.0f%% of required parts in vehicle stock — partial shortfall",
                            ratio * 100);
            case COLLECTABLE ->
                    String.format("%.0f%% on vehicle; shortfall collectable from nearby warehouse "
                                    + "(discount %.2f applied)",
                            ratio * 100, nearbyCollectableDiscount);
            case UNAVAILABLE ->
                    "required parts not confirmed in stock — advisory penalty only";
        };

        return new FactorBreakdown(CODE, ratio, normalisedValue, w, w * normalisedValue,
                explanation, false);
    }
}

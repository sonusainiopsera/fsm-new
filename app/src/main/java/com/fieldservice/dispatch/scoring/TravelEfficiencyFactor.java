package com.fieldservice.dispatch.scoring;

/**
 * Travel efficiency factor: rewards candidates closer to the work order site.
 *
 * <h3>Normalisation</h3>
 * <pre>
 *   normalised = max(0, 1 - estimatedMinutes / travelHorizonMinutes)
 * </pre>
 *
 * A technician at 0 minutes scores 1.0; one at or beyond the horizon scores 0.0.
 * The transform is monotone and inverse so shorter travel is always better.
 *
 * <h3>Degraded inputs</h3>
 * When the travel-time result is degraded (provider unavailable or geocoordinates
 * missing), the factor contributes a neutral value of 0.5 and sets the breakdown
 * {@code degraded} flag so the caller can surface a warning to the dispatcher.
 */
public class TravelEfficiencyFactor implements ScoringFactor {

    public static final String FACTOR_CODE = "TRAVEL_EFFICIENCY";
    static final double NEUTRAL_DEGRADED   = 0.5;

    @Override
    public String factorCode() { return FACTOR_CODE; }

    @Override
    public FactorBreakdown normalise(CandidateScoringData data, ScoringContext context) {
        TravelTimeResult travel = data.travelTime();

        if (travel == null || travel.degraded()) {
            return new FactorBreakdown(
                    FACTOR_CODE,
                    0.0,
                    NEUTRAL_DEGRADED,
                    0, 0,
                    "Travel estimate unavailable — neutral contribution applied",
                    true);
        }

        double raw        = travel.estimatedMinutes();
        double normalised = Math.max(0.0, 1.0 - raw / context.travelHorizonMinutes());
        String explanation = String.format(
                "Estimated travel %d min (horizon %d min) → %.0f%% efficiency",
                travel.estimatedMinutes(), context.travelHorizonMinutes(), normalised * 100);

        return new FactorBreakdown(FACTOR_CODE, raw, normalised, 0, 0, explanation, false);
    }
}

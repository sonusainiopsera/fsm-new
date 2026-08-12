package com.fieldservice.dispatch.scoring;

/**
 * Workload fairness factor: soft super-linear penalty for over-loaded technicians.
 *
 * <h3>Formula (per BR-03)</h3>
 * <pre>
 *   relativeOverload = max(0, (bookedHours - teamMean) / teamMean)
 *   penalty          = pow(relativeOverload, exponent)   // exponent > 1 → super-linear
 *   normalised       = max(0, 1 - penalty)
 * </pre>
 *
 * <p>A technician at or below the team mean receives normalised = 1.0 (no penalty).
 * A technician significantly above the mean receives a value approaching 0.0.
 * The super-linear exponent means light overload is lightly penalised and heavy
 * overload is disproportionately penalised, steering routine work to under-loaded
 * technicians without destroying travel efficiency.
 *
 * <h3>Zero-mean guard</h3>
 * When {@code teamMean == 0} every technician scores 1.0 (all equally loaded).
 * Division by zero is avoided; a note is included in the explanation.
 *
 * @param exponent super-linear exponent read from {@code dispatch_scoring_config};
 *                 must be > 1.0 (enforced by {@link ScoringWeights})
 */
public class WorkloadFairnessFactor implements ScoringFactor {

    public static final String FACTOR_CODE = "WORKLOAD_FAIRNESS";

    private final double exponent;

    public WorkloadFairnessFactor(double exponent) {
        if (exponent <= 1.0)
            throw new IllegalArgumentException(
                    "workloadExponent must be > 1.0 but was " + exponent);
        this.exponent = exponent;
    }

    public double getExponent() { return exponent; }

    @Override
    public String factorCode() { return FACTOR_CODE; }

    @Override
    public FactorBreakdown normalise(CandidateScoringData data, ScoringContext context) {
        double bookedHours = data.bookedHours();
        double teamMean    = context.teamMeanBookedHours();

        if (teamMean <= 0.0) {
            return new FactorBreakdown(
                    FACTOR_CODE, bookedHours, 1.0, 0, 0,
                    "All technicians equally loaded (team mean = 0) — no fairness penalty",
                    false);
        }

        double relativeOverload = Math.max(0.0, (bookedHours - teamMean) / teamMean);
        double penalty          = Math.pow(relativeOverload, exponent);
        double normalised       = Math.max(0.0, 1.0 - penalty);

        String explanation = String.format(
                "Booked %.1fh vs team mean %.1fh, overload %.2f → penalty %.3f (exponent %.2f)",
                bookedHours, teamMean, relativeOverload, penalty, exponent);

        return new FactorBreakdown(FACTOR_CODE, bookedHours, normalised, 0, 0, explanation, false);
    }
}

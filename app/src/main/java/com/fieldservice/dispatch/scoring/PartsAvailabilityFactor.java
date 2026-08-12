package com.fieldservice.dispatch.scoring;

/**
 * Parts availability factor: advisory soft contribution only.
 *
 * <p>The pre-computed {@code partsAvailabilityScore} from {@link CandidateScoringData}
 * is already normalised to [0,1] by the caller (1.0 when no parts are required,
 * proportional fraction otherwise). This factor passes it through directly so it
 * can never zero out or gate a candidate — it only provides a marginal ranking nudge.
 *
 * <p>Constraints: this factor must not be the sole reason for excluding any candidate
 * from the recommendation set.
 */
public class PartsAvailabilityFactor implements ScoringFactor {

    public static final String FACTOR_CODE = "PARTS_AVAILABILITY";

    @Override
    public String factorCode() { return FACTOR_CODE; }

    @Override
    public FactorBreakdown normalise(CandidateScoringData data, ScoringContext context) {
        double score = data.partsAvailabilityScore();
        String explanation = score >= 1.0
                ? "All required parts available (or none required)"
                : String.format("%.0f%% of required parts available at home depot", score * 100);

        return new FactorBreakdown(FACTOR_CODE, score, score, 0, 0, explanation, false);
    }
}

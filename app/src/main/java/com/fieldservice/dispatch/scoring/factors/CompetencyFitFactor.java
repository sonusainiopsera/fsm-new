package com.fieldservice.dispatch.scoring.factors;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoringContext;
import com.fieldservice.dispatch.scoring.ScoringFactor;
import com.fieldservice.dispatch.scoring.ScoringWeights;

/**
 * Competency fit factor.
 *
 * <p>Normalisation:
 * <ul>
 *   <li>Certification coverage (weight 0.7): fraction of required cert codes held.
 *       If no certs required, coverage = 1.0.</li>
 *   <li>Experience score (weight 0.3): min(priorJobTypeExperienceCount, 10) / 10.</li>
 * </ul>
 * Combined: {@code 0.7 * coverage + 0.3 * experienceScore}.
 *
 * <p>Raw value: certification coverage fraction [0,1].
 */
public class CompetencyFitFactor implements ScoringFactor {

    public static final String CODE = "COMPETENCY_FIT";
    private static final int EXPERIENCE_CAP = 10;

    @Override
    public String factorCode() {
        return CODE;
    }

    @Override
    public FactorBreakdown normalise(ScoringContext ctx, ScoringWeights weights) {
        double w = weights.weightFor(CODE);

        double certCoverage    = computeCertCoverage(ctx);
        double experienceScore = Math.min(ctx.priorJobTypeExperienceCount(), EXPERIENCE_CAP)
                / (double) EXPERIENCE_CAP;
        double normalised = 0.7 * certCoverage + 0.3 * experienceScore;

        int heldRequired = countHeldRequired(ctx);
        int totalRequired = ctx.requiredCertificationCodes().size();
        String explanation = String.format(
                "cert coverage=%.0f%% (%d/%d required), experience=%.0f%% (%d prior jobs, cap=%d)",
                certCoverage * 100, heldRequired, totalRequired,
                experienceScore * 100, ctx.priorJobTypeExperienceCount(), EXPERIENCE_CAP);

        return new FactorBreakdown(CODE, certCoverage, normalised, w,
                w * normalised, explanation, false);
    }

    private double computeCertCoverage(ScoringContext ctx) {
        int required = ctx.requiredCertificationCodes().size();
        if (required == 0) return 1.0;
        return (double) countHeldRequired(ctx) / required;
    }

    private int countHeldRequired(ScoringContext ctx) {
        int count = 0;
        for (String code : ctx.requiredCertificationCodes()) {
            if (ctx.heldCertificationCodes().contains(code)) count++;
        }
        return count;
    }
}

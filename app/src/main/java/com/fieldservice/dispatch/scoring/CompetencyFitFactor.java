package com.fieldservice.dispatch.scoring;

/**
 * Competency fit factor: measures certification coverage and prior job-type experience.
 *
 * <h3>Normalisation</h3>
 * <pre>
 *   certScore       = matchingCerts / max(1, requiredCerts)        ∈ [0,1]
 *   experienceScore = min(priorJobTypeExperience, EXPERIENCE_CAP) / EXPERIENCE_CAP  ∈ [0,1]
 *   normalised      = CERT_WEIGHT * certScore + EXPERIENCE_WEIGHT * experienceScore ∈ [0,1]
 * </pre>
 *
 * <ul>
 *   <li>CERT_WEIGHT = 0.7 — certifications are the primary hard-skills signal</li>
 *   <li>EXPERIENCE_WEIGHT = 0.3 — prior experience is a soft positive signal</li>
 *   <li>EXPERIENCE_CAP = 20 — marginal benefit of additional experience flattens above 20 jobs</li>
 * </ul>
 *
 * <p>When no certifications are required, certScore = 1.0 for all candidates.
 */
public class CompetencyFitFactor implements ScoringFactor {

    public static final String FACTOR_CODE   = "COMPETENCY_FIT";
    static final double CERT_WEIGHT          = 0.7;
    static final double EXPERIENCE_WEIGHT    = 0.3;
    static final int    EXPERIENCE_CAP       = 20;

    @Override
    public String factorCode() { return FACTOR_CODE; }

    @Override
    public FactorBreakdown normalise(CandidateScoringData data, ScoringContext context) {
        double certScore = computeCertScore(data, context);
        double expScore  = computeExperienceScore(data);
        double normalised = clamp(CERT_WEIGHT * certScore + EXPERIENCE_WEIGHT * expScore);

        String explanation = String.format(
                "Cert coverage %.0f%%, prior job-type experience %d (cap %d)",
                certScore * 100, data.priorJobTypeExperience(), EXPERIENCE_CAP);

        return new FactorBreakdown(FACTOR_CODE, certScore, normalised, 0, 0, explanation, false);
    }

    private static double computeCertScore(CandidateScoringData data, ScoringContext context) {
        if (context.requiredCertificationCodes().isEmpty()) return 1.0;
        long matched = context.requiredCertificationCodes().stream()
                .filter(data.certificationCodes()::contains)
                .count();
        return (double) matched / context.requiredCertificationCodes().size();
    }

    private static double computeExperienceScore(CandidateScoringData data) {
        return Math.min(data.priorJobTypeExperience(), EXPERIENCE_CAP) / (double) EXPERIENCE_CAP;
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}

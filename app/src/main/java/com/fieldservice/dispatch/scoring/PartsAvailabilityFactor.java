package com.fieldservice.dispatch.scoring;

import com.fieldservice.inventory.api.AvailabilityStatus;
import com.fieldservice.inventory.api.CandidateAvailability;

/**
 * Parts availability factor: advisory soft contribution only.
 *
 * <p>Scores are derived from the {@link AvailabilityStatus} of the candidate's vehicle location:
 * <ul>
 *   <li>FULLY_STOCKED → 1.0 (all parts on vehicle)</li>
 *   <li>COLLECTABLE   → 0.75 (parts available at reachable warehouse)</li>
 *   <li>PARTIALLY_STOCKED → 0.5 (some parts accessible, not all)</li>
 *   <li>UNAVAILABLE   → 0.0 (at least one part cannot be sourced)</li>
 * </ul>
 *
 * <p>When no parts are required or availability data is absent, the factor defaults to 1.0
 * (neutral — neither penalising nor boosting any candidate).
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
        CandidateAvailability availability = data.candidateAvailability();

        if (availability == null) {
            return new FactorBreakdown(FACTOR_CODE, 1.0, 1.0, 0, 0,
                    "No parts required or availability data not loaded", false, null);
        }

        double score = scoreFor(availability.status());
        String explanation = buildExplanation(availability);

        return new FactorBreakdown(FACTOR_CODE, score, score, 0, 0,
                explanation, false, availability);
    }

    static double scoreFor(AvailabilityStatus status) {
        return switch (status) {
            case FULLY_STOCKED    -> 1.0;
            case COLLECTABLE      -> 0.75;
            case PARTIALLY_STOCKED -> 0.5;
            case UNAVAILABLE      -> 0.0;
        };
    }

    private static String buildExplanation(CandidateAvailability availability) {
        return switch (availability.status()) {
            case FULLY_STOCKED -> "All required parts available on vehicle";
            case COLLECTABLE   -> String.format(
                    "%d part(s) short on vehicle but collectable from warehouse",
                    availability.shortfalls().size());
            case PARTIALLY_STOCKED -> String.format(
                    "%d of %d part shortfall(s) can be collected; %d unavailable anywhere",
                    collectableCount(availability),
                    availability.shortfalls().size(),
                    availability.shortfalls().size() - collectableCount(availability));
            case UNAVAILABLE -> String.format(
                    "%d required part(s) unavailable on vehicle or at any warehouse",
                    availability.shortfalls().size());
        };
    }

    private static int collectableCount(CandidateAvailability av) {
        return (int) av.shortfalls().stream()
                .filter(s -> s.available() >= s.requested())
                .count();
    }
}

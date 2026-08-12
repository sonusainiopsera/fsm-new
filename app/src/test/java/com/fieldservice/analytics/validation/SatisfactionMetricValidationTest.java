package com.fieldservice.analytics.validation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Validation tests for satisfaction metrics (CSAT and NPS).
 *
 * <p><strong>PENDING IMPLEMENTATION:</strong> The analytics module does not yet contain
 * satisfaction KPI calculators (no {@code satisfaction.csat} or {@code satisfaction.nps}
 * KpiAggregator was found at the time this test suite was authored). These stubs document
 * the expected behaviour per the WO-207 acceptance criteria.
 *
 * <p>Formulas (from PRD):
 * CSAT: average survey score over a rolling window.
 * NPS:  promoters (score >= 9) minus detractors (score <= 6) as a percentage of respondents.
 *
 * <p>Tests to implement when the calculators exist:
 * <ol>
 *   <li>CSAT averaging: sum of scores / count of responses per window.</li>
 *   <li>NPS: (promoters - detractors) / total * 100, range [-100, +100].</li>
 *   <li>Late-submitted survey does not retroactively alter a published baseline snapshot.</li>
 *   <li>Zero respondents returns null / not-available for both CSAT and NPS.</li>
 * </ol>
 */
@Tag("pending-implementation")
@DisplayName("Satisfaction Metrics Validation (CSAT + NPS) — PENDING calculator implementation")
class SatisfactionMetricValidationTest {

    @Test
    @Disabled("satisfaction.csat KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] CSAT = sum(scores) / count(responses)")
    void goldenDataset_csatAveraging_matchesExpected() {
        // TODO: inject CsatCalculator when implemented
        // TODO: seed golden dataset with survey responses
        // TODO: assert average score matches hand-derived value
    }

    @Test
    @Disabled("satisfaction.nps KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] NPS = (promoters - detractors) / total * 100")
    void goldenDataset_npsPromoterMinusDetractor_matchesExpected() {
        // TODO: seed promoters (score >= 9), passives (7-8), detractors (<= 6)
        // TODO: assert NPS = (promoterCount - detractorCount) / total * 100
    }

    @Test
    @Disabled("satisfaction.csat KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] late-submitted survey does not alter a published snapshot")
    void lateSubmittedSurvey_doesNotAlterPublishedSnapshot() {
        // TODO: seed a baseline snapshot for window W1
        // TODO: submit a late survey with survey_date inside W1 but submitted after W1 closed
        // TODO: assert the W1 snapshot value is unchanged
    }

    @Test
    @Disabled("satisfaction.csat KpiAggregator not yet implemented — stub for WO-207")
    @DisplayName("[STUB] zero respondents returns null not-available for CSAT and NPS")
    void zeroDenominator_noRespondents_returnsNull() {
        // TODO: assert value = null when no survey responses in window
    }
}

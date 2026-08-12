package com.fieldservice.workforce.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CertificationCohortClassifier} — no Spring context required.
 *
 * <p>Verifies exact boundary behaviour at 31, 30, 8, 7, 1, 0, -1 days and null expiresOn.
 */
class CertificationCohortClassifierTest {

    private static final LocalDate TODAY        = LocalDate.of(2026, 8, 12);
    private static final int       WARNING_DAYS = 30;
    private static final int       URGENT_DAYS  = 7;

    @Test
    @DisplayName("null expiresOn (perpetual) → empty")
    void nullExpiresOn_neverAlerts() {
        Optional<CertificationAlertStage> result =
                CertificationCohortClassifier.classify(null, TODAY, WARNING_DAYS, URGENT_DAYS);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("31 days remaining → outside warning window → empty")
    void thirtyOneDays_outsideWindow() {
        LocalDate expiresOn = TODAY.plusDays(31);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .isEmpty();
    }

    @Test
    @DisplayName("exactly 30 days remaining → WARNING boundary")
    void thirtyDays_warningBoundary() {
        LocalDate expiresOn = TODAY.plusDays(30);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.WARNING);
    }

    @Test
    @DisplayName("8 days remaining → WARNING cohort (inside warning, outside urgent)")
    void eightDays_warningCohort() {
        LocalDate expiresOn = TODAY.plusDays(8);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.WARNING);
    }

    @Test
    @DisplayName("exactly 7 days remaining → URGENT boundary")
    void sevenDays_urgentBoundary() {
        LocalDate expiresOn = TODAY.plusDays(7);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.URGENT);
    }

    @Test
    @DisplayName("1 day remaining → URGENT cohort")
    void oneDay_urgentCohort() {
        LocalDate expiresOn = TODAY.plusDays(1);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.URGENT);
    }

    @Test
    @DisplayName("0 days remaining (expires today) → URGENT cohort")
    void zeroDays_urgentCohort() {
        assertThat(CertificationCohortClassifier.classify(TODAY, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.URGENT);
    }

    @Test
    @DisplayName("minus 1 day (already expired) → EXPIRED")
    void minusOneDay_expired() {
        LocalDate expiresOn = TODAY.minusDays(1);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.EXPIRED);
    }

    @Test
    @DisplayName("far past expiry → EXPIRED")
    void farPast_expired() {
        LocalDate expiresOn = TODAY.minusDays(365);
        assertThat(CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS))
                .hasValue(CertificationAlertStage.EXPIRED);
    }

    @ParameterizedTest(name = "daysRemaining={0} → expected stage {1}")
    @CsvSource({
            "31, NONE",
            "30, WARNING",
            "29, WARNING",
            "8,  WARNING",
            "7,  URGENT",
            "6,  URGENT",
            "1,  URGENT",
            "0,  URGENT",
    })
    @DisplayName("Parametrized boundary sweep")
    void parametrizedBoundaries(int daysRemaining, String expectedStage) {
        LocalDate expiresOn = TODAY.plusDays(daysRemaining);
        Optional<CertificationAlertStage> result =
                CertificationCohortClassifier.classify(expiresOn, TODAY, WARNING_DAYS, URGENT_DAYS);

        if ("NONE".equals(expectedStage)) {
            assertThat(result).isEmpty();
        } else {
            assertThat(result).hasValue(CertificationAlertStage.valueOf(expectedStage));
        }
    }

    @Test
    @DisplayName("Custom window: warning=14, urgent=3")
    void customWindow() {
        assertThat(CertificationCohortClassifier.classify(TODAY.plusDays(15), TODAY, 14, 3))
                .isEmpty();
        assertThat(CertificationCohortClassifier.classify(TODAY.plusDays(14), TODAY, 14, 3))
                .hasValue(CertificationAlertStage.WARNING);
        assertThat(CertificationCohortClassifier.classify(TODAY.plusDays(3), TODAY, 14, 3))
                .hasValue(CertificationAlertStage.URGENT);
        assertThat(CertificationCohortClassifier.classify(TODAY.minusDays(1), TODAY, 14, 3))
                .hasValue(CertificationAlertStage.EXPIRED);
    }
}

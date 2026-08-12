package com.fieldservice.workforce.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CohortClassifier} — no Spring context required (AC-11).
 *
 * <p>Validates exact day boundaries to ensure:
 * <ul>
 *   <li>30 days → WARNING; 31 days → no cohort</li>
 *   <li>7 days → URGENT; 8 days → WARNING (not URGENT)</li>
 *   <li>0 and negative → EXPIRED</li>
 *   <li>null expires_on → no cohort (AC-3)</li>
 * </ul>
 */
class CohortClassifierTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 12);
    private final CohortClassifier classifier = new CohortClassifier(30, 7);

    // ── Null expires_on ───────────────────────────────────────────────────────

    @Test
    @DisplayName("null expires_on produces no cohort")
    void nullExpiresOn_noStage() {
        assertThat(classifier.classify(null, BUSINESS_DATE)).isEmpty();
    }

    // ── Outside warning window ────────────────────────────────────────────────

    @Test
    @DisplayName("31 days to expiry is outside window — no cohort")
    void thirtyOneDays_noStage() {
        LocalDate expiresOn = BUSINESS_DATE.plusDays(31);
        assertThat(classifier.classify(expiresOn, BUSINESS_DATE)).isEmpty();
    }

    // ── Warning boundary ──────────────────────────────────────────────────────

    @Test
    @DisplayName("exactly 30 days to expiry triggers WARNING")
    void thirtyDays_warning() {
        LocalDate expiresOn = BUSINESS_DATE.plusDays(30);
        assertThat(classifier.classify(expiresOn, BUSINESS_DATE))
                .contains(CertificationAlertStage.WARNING);
    }

    // ── Between urgent and warning ────────────────────────────────────────────

    @Test
    @DisplayName("8 days to expiry triggers WARNING (between urgent=7 and warning=30)")
    void eightDays_warning() {
        LocalDate expiresOn = BUSINESS_DATE.plusDays(8);
        assertThat(classifier.classify(expiresOn, BUSINESS_DATE))
                .contains(CertificationAlertStage.WARNING);
    }

    // ── Urgent boundary ───────────────────────────────────────────────────────

    @Test
    @DisplayName("exactly 7 days to expiry triggers URGENT")
    void sevenDays_urgent() {
        LocalDate expiresOn = BUSINESS_DATE.plusDays(7);
        assertThat(classifier.classify(expiresOn, BUSINESS_DATE))
                .contains(CertificationAlertStage.URGENT);
    }

    @Test
    @DisplayName("1 day to expiry triggers URGENT")
    void oneDay_urgent() {
        LocalDate expiresOn = BUSINESS_DATE.plusDays(1);
        assertThat(classifier.classify(expiresOn, BUSINESS_DATE))
                .contains(CertificationAlertStage.URGENT);
    }

    // ── Expired boundary ──────────────────────────────────────────────────────

    @Test
    @DisplayName("0 days to expiry (expires today) triggers EXPIRED")
    void zeroDays_expired() {
        assertThat(classifier.classify(BUSINESS_DATE, BUSINESS_DATE))
                .contains(CertificationAlertStage.EXPIRED);
    }

    @Test
    @DisplayName("minus 1 day (already expired) triggers EXPIRED")
    void minusOneDay_expired() {
        LocalDate expiresOn = BUSINESS_DATE.minusDays(1);
        assertThat(classifier.classify(expiresOn, BUSINESS_DATE))
                .contains(CertificationAlertStage.EXPIRED);
    }

    // ── Parameterized boundary sweep ──────────────────────────────────────────

    @ParameterizedTest(name = "daysOffset={0} → {1}")
    @CsvSource({
            "31, NONE",
            "30, WARNING",
            "29, WARNING",
            "8,  WARNING",
            "7,  URGENT",
            "6,  URGENT",
            "1,  URGENT",
            "0,  EXPIRED",
            "-1, EXPIRED",
            "-10,EXPIRED"
    })
    @DisplayName("parameterized boundary sweep")
    void parameterizedBoundaries(int daysOffset, String expectedStage) {
        LocalDate expiresOn = daysOffset >= 0
                ? BUSINESS_DATE.plusDays(daysOffset)
                : BUSINESS_DATE.minusDays(-daysOffset);

        Optional<CertificationAlertStage> result = classifier.classify(expiresOn, BUSINESS_DATE);

        if ("NONE".equals(expectedStage)) {
            assertThat(result).isEmpty();
        } else {
            assertThat(result).contains(CertificationAlertStage.valueOf(expectedStage));
        }
    }

    // ── Constructor guard ─────────────────────────────────────────────────────

    @Test
    @DisplayName("urgentWindowDays >= warningWindowDays throws")
    void constructor_urgentNotLessThanWarning_throws() {
        assertThatThrownBy(() -> new CohortClassifier(7, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CohortClassifier(7, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

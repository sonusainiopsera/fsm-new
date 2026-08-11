package com.fieldservice.workforce.application;

import com.fieldservice.workforce.application.CertificationEvaluator.CertSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CertificationEvaluator} — no Spring context.
 *
 * <p>Verifies boundary semantics:
 * <ul>
 *   <li>Certified on the expiry date (inclusive)</li>
 *   <li>Not certified the day after the expiry date (no grace period)</li>
 *   <li>Null expires_on means perpetual (always current)</li>
 *   <li>Inactive certification is ignored</li>
 *   <li>Missing certification returns false</li>
 *   <li>Multi-type required set — partial match is not enough</li>
 *   <li>Empty required set returns empty missing set</li>
 *   <li>Regulated vs non-regulated handled identically by the evaluator (regulated check is in guard)</li>
 * </ul>
 */
@DisplayName("CertificationEvaluator unit tests (no Spring context)")
class CertificationEvaluatorTest {

    private CertificationEvaluator evaluator;

    private static final LocalDate DATE_JAN_30 = LocalDate.of(2026, 1, 30);
    private static final LocalDate DATE_JAN_31 = LocalDate.of(2026, 1, 31);
    private static final LocalDate DATE_FEB_01 = LocalDate.of(2026, 2, 1);

    @BeforeEach
    void setUp() {
        evaluator = new CertificationEvaluator();
    }

    // ── Boundary: exact expiry date ──────────────────────────────────────────

    @Test
    @DisplayName("isCurrent: certification with expires_on equal to evaluation date is CURRENT (AC-4)")
    void isCurrent_onExpiryDate_isTrue() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 30), DATE_JAN_30));

        assertThat(evaluator.isCurrent(snaps, "GAS_SAFE", DATE_JAN_30)).isTrue();
    }

    @Test
    @DisplayName("isCurrent: certification evaluated one day after expires_on is NOT CURRENT (AC-4)")
    void isCurrent_dayAfterExpiry_isFalse() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 30), DATE_JAN_30));

        assertThat(evaluator.isCurrent(snaps, "GAS_SAFE", DATE_FEB_01)).isFalse();
    }

    @Test
    @DisplayName("isCurrent: no grace period — expired is not current even by one day (regulated)")
    void isCurrent_noGracePeriod_regulated() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 1), DATE_JAN_31));

        assertThat(evaluator.isCurrent(snaps, "GAS_SAFE", DATE_FEB_01)).isFalse();
    }

    @Test
    @DisplayName("isCurrent: no grace period — expired is not current even by one day (non-regulated)")
    void isCurrent_noGracePeriod_nonRegulated() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("FIRST_AID", false, true, LocalDate.of(2025, 1, 1), DATE_JAN_31));

        assertThat(evaluator.isCurrent(snaps, "FIRST_AID", DATE_FEB_01)).isFalse();
    }

    // ── Null expires_on = perpetual ──────────────────────────────────────────

    @Test
    @DisplayName("isCurrent: null expires_on is always current (perpetual competency)")
    void isCurrent_nullExpiresOn_alwaysCurrent() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("ELECTRICAL_18TH", true, true, LocalDate.of(2020, 1, 1), null));

        assertThat(evaluator.isCurrent(snaps, "ELECTRICAL_18TH", LocalDate.of(2030, 12, 31))).isTrue();
    }

    // ── Inactive certification ───────────────────────────────────────────────

    @Test
    @DisplayName("isCurrent: inactive certification is not counted even if date is valid")
    void isCurrent_inactiveCert_isFalse() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, false, LocalDate.of(2025, 1, 1), DATE_FEB_01));

        assertThat(evaluator.isCurrent(snaps, "GAS_SAFE", DATE_JAN_30)).isFalse();
    }

    // ── Missing certification ────────────────────────────────────────────────

    @Test
    @DisplayName("isCurrent: no certification at all returns false")
    void isCurrent_noCertification_isFalse() {
        assertThat(evaluator.isCurrent(List.of(), "GAS_SAFE", DATE_JAN_30)).isFalse();
    }

    // ── Two certs of same type: one expired, one current ────────────────────

    @Test
    @DisplayName("isCurrent: two certs same type — one expired, one current — resolves to current (AC-edge)")
    void isCurrent_twoSameType_oneCurrentOneExpired_isCurrent() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2024, 1, 1), DATE_JAN_30),  // expiring exactly on eval date
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 1)) // already expired
        );
        // eval date is JAN_30 so first cert is still current
        assertThat(evaluator.isCurrent(snaps, "GAS_SAFE", DATE_JAN_30)).isTrue();
    }

    // ── findMissingCodes ─────────────────────────────────────────────────────

    @Test
    @DisplayName("findMissingCodes: empty required set returns empty missing set")
    void findMissing_emptyRequired_empty() {
        assertThat(evaluator.findMissingCodes(List.of(), Set.of(), DATE_JAN_30)).isEmpty();
    }

    @Test
    @DisplayName("findMissingCodes: all satisfied returns empty")
    void findMissing_allSatisfied_empty() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 1), DATE_FEB_01),
                new CertSnapshot("FIRST_AID", false, true, LocalDate.of(2025, 1, 1), DATE_FEB_01));

        assertThat(evaluator.findMissingCodes(snaps, Set.of("GAS_SAFE", "FIRST_AID"), DATE_JAN_30)).isEmpty();
    }

    @Test
    @DisplayName("findMissingCodes: partial match returns only missing codes")
    void findMissing_partial_returnsMissingOnly() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 1), DATE_FEB_01));

        Set<String> missing = evaluator.findMissingCodes(
                snaps, Set.of("GAS_SAFE", "REFRIGERANT_F_GAS"), DATE_JAN_30);

        assertThat(missing).containsExactly("REFRIGERANT_F_GAS");
    }

    @Test
    @DisplayName("findMissingCodes: expired cert for required code is counted as missing")
    void findMissing_expiredCert_countedMissing() {
        List<CertSnapshot> snaps = List.of(
                new CertSnapshot("GAS_SAFE", true, true, LocalDate.of(2025, 1, 1), DATE_JAN_31));
        // evaluating on FEB_01, so it's expired
        assertThat(evaluator.findMissingCodes(snaps, Set.of("GAS_SAFE"), DATE_FEB_01))
                .containsExactly("GAS_SAFE");
    }

    // ── daysUntilExpiry ──────────────────────────────────────────────────────

    @Test
    @DisplayName("daysUntilExpiry: null expiresOn returns null (perpetual)")
    void daysUntilExpiry_null() {
        assertThat(evaluator.daysUntilExpiry(null, DATE_JAN_30)).isNull();
    }

    @Test
    @DisplayName("daysUntilExpiry: expiry in future returns positive days")
    void daysUntilExpiry_future() {
        assertThat(evaluator.daysUntilExpiry(DATE_FEB_01, DATE_JAN_30)).isEqualTo(2L);
    }

    @Test
    @DisplayName("daysUntilExpiry: expiry today returns 0")
    void daysUntilExpiry_today() {
        assertThat(evaluator.daysUntilExpiry(DATE_JAN_30, DATE_JAN_30)).isEqualTo(0L);
    }

    @Test
    @DisplayName("daysUntilExpiry: expiry yesterday returns negative days")
    void daysUntilExpiry_past() {
        assertThat(evaluator.daysUntilExpiry(DATE_JAN_30, DATE_FEB_01)).isEqualTo(-2L);
    }
}

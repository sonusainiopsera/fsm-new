package com.fieldservice.workforce.internal;

import com.fieldservice.workforce.internal.CompletenessEvaluator.CertEntry;
import com.fieldservice.workforce.internal.CompletenessEvaluator.RequirementSpec;
import com.fieldservice.workforce.internal.CompletenessEvaluator.TechnicianCompletenessResult;
import com.fieldservice.workforce.internal.CompletenessEvaluator.TechnicianProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CompletenessEvaluator} — no Spring context (AC-10).
 *
 * <p>Business date: 2026-08-12 (consistent with other fixture business dates).
 * warning_window_days: 30.
 */
class CompletenessEvaluatorTest {

    private static final LocalDate BUSINESS_DATE    = LocalDate.of(2026, 8, 12);
    private static final int       WARNING_WINDOW   = 30;
    private static final UUID      TECH_ID          = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private final CompletenessEvaluator evaluator = new CompletenessEvaluator();

    private TechnicianProfile profile(String employeeNo, String displayName) {
        return new TechnicianProfile(TECH_ID, employeeNo, displayName, "test@test.com", "07700000000", null);
    }

    private RequirementSpec fieldReq(String fieldName) {
        return new RequirementSpec(RequirementKind.PROFILE_FIELD, fieldName, null);
    }

    private RequirementSpec certReq(String typeCode) {
        return new RequirementSpec(RequirementKind.CERTIFICATION_TYPE, null, typeCode);
    }

    // ── Profile field evaluation ───────────────────────────────────────────

    @Test
    @DisplayName("null employee_no → missingFields contains employee_no")
    void nullEmployeeNo_missing() {
        TechnicianProfile p = profile(null, "John Doe");
        TechnicianCompletenessResult r = evaluator.evaluate(
                p, List.of(), List.of(fieldReq("employee_no")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingFields()).containsExactly("employee_no");
        assertThat(r.isComplete()).isFalse();
    }

    @Test
    @DisplayName("blank employee_no → missingFields contains employee_no")
    void blankEmployeeNo_missing() {
        TechnicianProfile p = profile("  ", "John Doe");
        TechnicianCompletenessResult r = evaluator.evaluate(
                p, List.of(), List.of(fieldReq("employee_no")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingFields()).containsExactly("employee_no");
    }

    @Test
    @DisplayName("present employee_no → missingFields is empty")
    void presentEmployeeNo_notMissing() {
        TechnicianProfile p = profile("EMP-001", "John Doe");
        TechnicianCompletenessResult r = evaluator.evaluate(
                p, List.of(), List.of(fieldReq("employee_no")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingFields()).isEmpty();
    }

    @Test
    @DisplayName("unknown field name → treated as missing (fail-closed)")
    void unknownFieldName_treatedAsMissing() {
        TechnicianProfile p = profile("EMP-001", "John Doe");
        TechnicianCompletenessResult r = evaluator.evaluate(
                p, List.of(), List.of(fieldReq("unknown_field")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingFields()).containsExactly("unknown_field");
    }

    // ── Certification evaluation ───────────────────────────────────────────

    @Test
    @DisplayName("no cert for required type → missingCertificationTypes")
    void noCertForRequiredType_missing() {
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingCertificationTypes()).containsExactly("GAS_SAFE");
        assertThat(r.isComplete()).isFalse();
    }

    @Test
    @DisplayName("current cert (expires far future) → complete")
    void currentCert_complete() {
        CertEntry cert = new CertEntry("GAS_SAFE", LocalDate.of(2099, 12, 31), true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(cert), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingCertificationTypes()).isEmpty();
        assertThat(r.expiredCertificationTypes()).isEmpty();
        assertThat(r.isComplete()).isTrue();
    }

    @Test
    @DisplayName("perpetual cert (null expires_on) → complete, not expiring soon")
    void perpetualCert_complete() {
        CertEntry cert = new CertEntry("GAS_SAFE", null, true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(cert), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.isComplete()).isTrue();
        assertThat(r.expiringSoonCertificationTypes()).isEmpty();
    }

    @Test
    @DisplayName("expired cert → expiredCertificationTypes")
    void expiredCert_expiredList() {
        CertEntry cert = new CertEntry("GAS_SAFE", LocalDate.of(2020, 1, 1), true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(cert), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.expiredCertificationTypes()).containsExactly("GAS_SAFE");
        assertThat(r.isComplete()).isFalse();
    }

    @Test
    @DisplayName("cert expiring on business date (0 days) → EXPIRED")
    void certExpiringToday_expired() {
        CertEntry cert = new CertEntry("GAS_SAFE", BUSINESS_DATE.minusDays(1), true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(cert), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.expiredCertificationTypes()).containsExactly("GAS_SAFE");
    }

    @Test
    @DisplayName("cert expiring in 30 days → expiringSoon (but still complete)")
    void certExpiring30Days_expiringSoonButComplete() {
        CertEntry cert = new CertEntry("GAS_SAFE", BUSINESS_DATE.plusDays(30), true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(cert), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.expiringSoonCertificationTypes()).containsExactly("GAS_SAFE");
        assertThat(r.expiredCertificationTypes()).isEmpty();
        assertThat(r.isComplete()).isTrue();  // expiring soon ≠ incomplete
    }

    @Test
    @DisplayName("cert expiring in 31 days → NOT expiring soon")
    void certExpiring31Days_notExpiringSoon() {
        CertEntry cert = new CertEntry("GAS_SAFE", BUSINESS_DATE.plusDays(31), true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(cert), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.expiringSoonCertificationTypes()).isEmpty();
        assertThat(r.isComplete()).isTrue();
    }

    @Test
    @DisplayName("one expired cert + one current cert for same type → technician is complete")
    void multipleCardsForType_currentWins() {
        CertEntry expired = new CertEntry("GAS_SAFE", LocalDate.of(2020, 1, 1), true);
        CertEntry current = new CertEntry("GAS_SAFE", LocalDate.of(2099, 12, 31), true);
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile("EMP-001", "John"), List.of(expired, current), List.of(certReq("GAS_SAFE")), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.expiredCertificationTypes()).isEmpty();
        assertThat(r.isComplete()).isTrue();
    }

    // ── Aggregate arithmetic ───────────────────────────────────────────────

    @Test
    @DisplayName("missing field AND missing cert → both listed")
    void missingFieldAndMissingCert_bothListed() {
        TechnicianProfile p = profile(null, "John");
        TechnicianCompletenessResult r = evaluator.evaluate(
                p, List.of(),
                List.of(fieldReq("employee_no"), certReq("GAS_SAFE")),
                BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.missingFields()).containsExactly("employee_no");
        assertThat(r.missingCertificationTypes()).containsExactly("GAS_SAFE");
        assertThat(r.isComplete()).isFalse();
    }

    @Test
    @DisplayName("empty requirements list → technician is complete (no requirements = all met)")
    void emptyRequirements_complete() {
        TechnicianCompletenessResult r = evaluator.evaluate(
                profile(null, null), List.of(), List.of(), BUSINESS_DATE, WARNING_WINDOW);
        assertThat(r.isComplete()).isTrue();
    }
}

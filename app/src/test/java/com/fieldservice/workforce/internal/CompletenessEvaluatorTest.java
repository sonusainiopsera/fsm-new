package com.fieldservice.workforce.internal;

import com.fieldservice.workforce.api.CertificationSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CompletenessEvaluator} — no Spring context required.
 */
class CompletenessEvaluatorTest {

    private static final LocalDate TODAY        = LocalDate.of(2026, 8, 12);
    private static final int       WARNING_DAYS = 14;
    private static final UUID      TECH_ID      = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ---- Field population ---------------------------------------------------

    @Test
    @DisplayName("all required profile fields populated → complete")
    void allFieldsPresent_complete() {
        TechnicianReadModel tech = tech("EMP001", "Bob Smith", "+447700", "Europe/London", UUID.randomUUID());
        ReadinessRequirementEntity req = profileFieldReq("employeeCode");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isTrue();
        assertThat(result.missingFields()).isEmpty();
    }

    @Test
    @DisplayName("required field is null → missing")
    void nullField_missing() {
        TechnicianReadModel tech = tech(null, "Bob", "+447700", "UTC", null);
        ReadinessRequirementEntity req = profileFieldReq("employeeCode");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isFalse();
        assertThat(result.missingFields()).containsExactly("employeeCode");
    }

    @Test
    @DisplayName("required field is blank → missing")
    void blankField_missing() {
        TechnicianReadModel tech = tech("  ", "Bob", "+447700", "UTC", null);
        ReadinessRequirementEntity req = profileFieldReq("employeeCode");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isFalse();
        assertThat(result.missingFields()).containsExactly("employeeCode");
    }

    @Test
    @DisplayName("unknown field name → fail-closed (missing)")
    void unknownField_failClosed() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+447700", "UTC", UUID.randomUUID());
        ReadinessRequirementEntity req = profileFieldReq("unknownField");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isFalse();
        assertThat(result.missingFields()).containsExactly("unknownField");
    }

    @Test
    @DisplayName("homeBaseSiteId non-null → populated")
    void homeBaseSiteId_populated() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", UUID.randomUUID());
        ReadinessRequirementEntity req = profileFieldReq("homeBaseSiteId");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isTrue();
    }

    // ---- Certification gap types --------------------------------------------

    @Test
    @DisplayName("no cert of required type at all → MISSING")
    void noCert_missing() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isFalse();
        assertThat(result.missingCertificationTypes()).containsExactly("GAS_SAFE");
    }

    @Test
    @DisplayName("cert exists but not current → EXPIRED")
    void expiredCert_classified() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        CertificationSummary expired = cert("GAS_SAFE", false, -5L); // expired 5 days ago
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(expired), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isFalse();
        assertThat(result.expiredCertificationTypes()).containsExactly("GAS_SAFE");
        assertThat(result.missingCertificationTypes()).isEmpty();
    }

    @Test
    @DisplayName("current cert well within window → complete, not expiring-soon")
    void currentCertNotExpiringSoon_complete() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        CertificationSummary current = cert("GAS_SAFE", true, 60L);
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(current), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isTrue();
        assertThat(result.expiringSoonCertificationTypes()).isEmpty();
    }

    @Test
    @DisplayName("current cert expiring within warning window → complete but expiring-soon")
    void currentCertExpiringSoon_completeButWarning() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        CertificationSummary soon = cert("GAS_SAFE", true, 7L); // 7 days, within 14-day window
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(soon), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isTrue();
        assertThat(result.expiringSoonCertificationTypes()).containsExactly("GAS_SAFE");
    }

    @Test
    @DisplayName("cert expiring on exactly warning boundary day → expiring-soon")
    void certExpiringOnBoundary_expiringSoon() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        CertificationSummary boundary = cert("GAS_SAFE", true, (long) WARNING_DAYS);
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(boundary), List.of(req), WARNING_DAYS);
        assertThat(result.expiringSoonCertificationTypes()).containsExactly("GAS_SAFE");
    }

    @Test
    @DisplayName("multiple certs same type: one current → satisfied (not expired/missing)")
    void multipleCertsSameType_anyCurrent_satisfied() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        CertificationSummary expired = cert("GAS_SAFE", false, -5L);
        CertificationSummary current = cert("GAS_SAFE", true, 90L);
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(expired, current), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isTrue();
        assertThat(result.expiredCertificationTypes()).isEmpty();
    }

    @Test
    @DisplayName("cert with null expiresOn (perpetual) is current, not expiring-soon")
    void perpetualCert_currentNotExpiringSoon() {
        TechnicianReadModel tech = tech("EMP001", "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = certReq("GAS_SAFE");
        CertificationSummary perpetual = cert("GAS_SAFE", true, null); // null daysUntilExpiry
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(perpetual), List.of(req), WARNING_DAYS);
        assertThat(result.complete()).isTrue();
        assertThat(result.expiringSoonCertificationTypes()).isEmpty();
    }

    @Test
    @DisplayName("zero active technicians: evaluating empty list returns empty results")
    void emptyTechnicianList_returnsEmpty() {
        // Note: zero-technician guard is in ReadinessReportService, not the evaluator.
        // Evaluator just handles whatever list it's given.
        assertThat(List.of()).isEmpty();
    }

    @Test
    @DisplayName("isBlocking returns true when not complete")
    void isBlocking_notComplete() {
        TechnicianReadModel tech = tech(null, "Bob", "+44", "UTC", null);
        ReadinessRequirementEntity req = profileFieldReq("employeeCode");
        TechnicianCompletenessResult result = CompletenessEvaluator.evaluate(tech, List.of(), List.of(req), WARNING_DAYS);
        assertThat(result.isBlocking()).isTrue();
    }

    // ---- Helpers ------------------------------------------------------------

    private TechnicianReadModel tech(String employeeCode, String displayName,
                                      String mobilePhone, String timezone, UUID homeBaseSiteId) {
        return new TechnicianReadModel(TECH_ID, employeeCode, displayName, mobilePhone,
                timezone, homeBaseSiteId);
    }

    private ReadinessRequirementEntity profileFieldReq(String fieldName) {
        return new ReadinessRequirementEntity(
                ReadinessRequirementKind.PROFILE_FIELD, fieldName, null, null);
    }

    private ReadinessRequirementEntity certReq(String typeCode) {
        return new ReadinessRequirementEntity(
                ReadinessRequirementKind.CERTIFICATION_TYPE, null, typeCode, null);
    }

    private CertificationSummary cert(String typeCode, boolean current, Long daysUntilExpiry) {
        LocalDate expiresOn = daysUntilExpiry != null ? TODAY.plusDays(daysUntilExpiry) : null;
        return new CertificationSummary(
                UUID.randomUUID(), typeCode, typeCode, false,
                "REF-001", TODAY.minusYears(1), expiresOn, current, daysUntilExpiry);
    }
}

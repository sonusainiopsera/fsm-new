package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for EligibilityFilter — no Spring context, no JPA, no HTTP.
 *
 * <p>Uses a fixed Clock so expiry boundaries are fully deterministic.
 */
class EligibilityFilterTest {

    // Fixed evaluation instant: 2026-01-15T12:00:00Z (Thursday, ISO day 4)
    private static final Instant EVAL_INSTANT  = Instant.parse("2026-01-15T12:00:00Z");
    private static final Instant WINDOW_END    = Instant.parse("2026-01-15T16:00:00Z");
    private static final LocalDate EVAL_DATE   = LocalDate.of(2026, 1, 15);

    private static final double SITE_LAT = 51.5;
    private static final double SITE_LON = -0.1;
    private static final double MAX_REACH_KM = 50.0;

    private EligibilityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EligibilityFilter(Clock.fixed(EVAL_INSTANT, ZoneOffset.UTC));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WorkOrderRequirements requirements(List<String> certCodes) {
        return new WorkOrderRequirements(
                certCodes, EVAL_INSTANT, WINDOW_END, SITE_LAT, SITE_LON, MAX_REACH_KM);
    }

    /** Monday (ISO 1) 08:00–18:00, no end date */
    private static TechnicianCandidate.AvailabilityWindow thursdayWindow() {
        // EVAL_DATE is a Thursday (ISO day 4)
        return new TechnicianCandidate.AvailabilityWindow(
                4, LocalTime.of(8, 0), LocalTime.of(18, 0),
                LocalDate.of(2025, 1, 1), null);
    }

    /** Home base at central London — ~0 km from SITE_LAT/LON */
    private static TechnicianCandidate activeCandidate(UUID id, List<CertificationRecord> certs) {
        return new TechnicianCandidate(id, true, "UTC", certs,
                List.of(thursdayWindow()), List.of(), 51.51, -0.12);
    }

    // ── Test cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("eligible when all requirements satisfied")
    void allRequirementsSatisfied_eligible() {
        UUID id = UUID.randomUUID();
        CertificationRecord cert = new CertificationRecord("ELEC", EVAL_DATE.plusYears(1));
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(cert))),
                requirements(List.of("ELEC")));

        assertThat(result.eligible()).containsExactly(id);
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    @DisplayName("INACTIVE_TECHNICIAN when active=false")
    void inactiveTechnician_excluded() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate inactive = new TechnicianCandidate(
                id, false, "UTC", List.of(), List.of(), List.of(), 51.5, -0.1);
        EligibilityResult result = filter.filter(List.of(inactive), requirements(List.of()));

        assertThat(result.eligible()).isEmpty();
        assertThat(result.excluded()).hasSize(1);
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.INACTIVE_TECHNICIAN);
    }

    @Test
    @DisplayName("CERTIFICATION_MISSING when no cert of required type exists")
    void certMissing_excluded() {
        UUID id = UUID.randomUUID();
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of())),
                requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_MISSING);
    }

    @Test
    @DisplayName("CERTIFICATION_EXPIRED when cert exists but expired before window start")
    void certExpired_excluded() {
        UUID id = UUID.randomUUID();
        // expiresOn = day before evaluation date
        CertificationRecord expired = new CertificationRecord("ELEC", EVAL_DATE.minusDays(1));
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(expired))),
                requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
    }

    @Test
    @DisplayName("AC-2: eligible at 23:59:59 on expiry day (LocalDate boundary)")
    void certExpiryBoundary_eligibleAtEndOfDay() {
        // Service window starts at 23:59:59 UTC on 2026-01-15
        Instant windowAtEndOfDay = Instant.parse("2026-01-15T23:59:59Z");
        WorkOrderRequirements req = new WorkOrderRequirements(
                List.of("ELEC"), windowAtEndOfDay,
                Instant.parse("2026-01-16T03:59:59Z"),
                SITE_LAT, SITE_LON, MAX_REACH_KM);

        UUID id = UUID.randomUUID();
        // Cert expires 2026-01-15 — still current (15 >= 15)
        CertificationRecord cert = new CertificationRecord("ELEC", LocalDate.of(2026, 1, 15));
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(cert))), req);

        assertThat(result.eligible()).containsExactly(id);
    }

    @Test
    @DisplayName("AC-2: ineligible at 00:00:00 the next day (LocalDate boundary)")
    void certExpiryBoundary_ineligibleNextDay() {
        Instant nextDayMidnight = Instant.parse("2026-01-16T00:00:00Z");
        WorkOrderRequirements req = new WorkOrderRequirements(
                List.of("ELEC"), nextDayMidnight,
                Instant.parse("2026-01-16T04:00:00Z"),
                SITE_LAT, SITE_LON, MAX_REACH_KM);

        UUID id = UUID.randomUUID();
        // Cert expires 2026-01-15 — expired (15 < 16)
        CertificationRecord cert = new CertificationRecord("ELEC", LocalDate.of(2026, 1, 15));
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(cert))), req);

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
    }

    @Test
    @DisplayName("eligible when cert has no expiry date (non-expiring)")
    void certNoExpiry_eligible() {
        UUID id = UUID.randomUUID();
        CertificationRecord noExpiry = new CertificationRecord("ELEC", null);
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(noExpiry))),
                requirements(List.of("ELEC")));

        assertThat(result.eligible()).containsExactly(id);
    }

    @Test
    @DisplayName("two certs same type: one expired, one current → eligible")
    void duplicateCertsOneCurrent_eligible() {
        UUID id = UUID.randomUUID();
        CertificationRecord expired = new CertificationRecord("ELEC", EVAL_DATE.minusDays(1));
        CertificationRecord current = new CertificationRecord("ELEC", EVAL_DATE.plusYears(1));
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(expired, current))),
                requirements(List.of("ELEC")));

        assertThat(result.eligible()).containsExactly(id);
    }

    @Test
    @DisplayName("eligible when empty required-certification set — no certification exclusions")
    void emptyCertRequirements_allEligible() {
        UUID id = UUID.randomUUID();
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of())),
                requirements(List.of()));

        assertThat(result.eligible()).containsExactly(id);
    }

    @Test
    @DisplayName("UNAVAILABLE_IN_WINDOW when no shift windows exist")
    void noShiftWindows_unavailable() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate noWindows = new TechnicianCandidate(
                id, true, "UTC",
                List.of(new CertificationRecord("ELEC", EVAL_DATE.plusYears(1))),
                List.of(), List.of(), 51.5, -0.1);
        EligibilityResult result = filter.filter(
                List.of(noWindows), requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    @DisplayName("UNAVAILABLE_IN_WINDOW when approved absence overlaps service window")
    void absenceOverlap_unavailable() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate.Absence overlap = new TechnicianCandidate.Absence(
                EVAL_INSTANT.minusSeconds(3600), WINDOW_END.plusSeconds(3600));
        TechnicianCandidate candidate = new TechnicianCandidate(
                id, true, "UTC",
                List.of(new CertificationRecord("ELEC", EVAL_DATE.plusYears(1))),
                List.of(thursdayWindow()), List.of(overlap), 51.5, -0.1);
        EligibilityResult result = filter.filter(
                List.of(candidate), requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    @DisplayName("UNAVAILABLE_IN_WINDOW when shift is on wrong day of week")
    void shiftWrongDay_unavailable() {
        UUID id = UUID.randomUUID();
        // Monday window (ISO 1), but EVAL_DATE is Thursday (ISO 4)
        TechnicianCandidate.AvailabilityWindow mondayWindow = new TechnicianCandidate.AvailabilityWindow(
                1, LocalTime.of(8, 0), LocalTime.of(18, 0),
                LocalDate.of(2025, 1, 1), null);
        TechnicianCandidate candidate = new TechnicianCandidate(
                id, true, "UTC",
                List.of(new CertificationRecord("ELEC", EVAL_DATE.plusYears(1))),
                List.of(mondayWindow), List.of(), 51.5, -0.1);
        EligibilityResult result = filter.filter(
                List.of(candidate), requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    @DisplayName("OUT_OF_REACH when Haversine distance exceeds maxReachKm")
    void outOfReach_excluded() {
        UUID id = UUID.randomUUID();
        // Edinburgh (~530 km from London)
        TechnicianCandidate remote = new TechnicianCandidate(
                id, true, "UTC",
                List.of(new CertificationRecord("ELEC", EVAL_DATE.plusYears(1))),
                List.of(thursdayWindow()), List.of(), 55.95, -3.19);
        EligibilityResult result = filter.filter(
                List.of(remote), requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.OUT_OF_REACH);
    }

    @Test
    @DisplayName("reach filter skipped and reachUnknown=true when site has no coordinates")
    void siteNoCoords_reachFilterSkipped() {
        UUID id = UUID.randomUUID();
        WorkOrderRequirements noCoords = new WorkOrderRequirements(
                List.of("ELEC"), EVAL_INSTANT, WINDOW_END, null, null, MAX_REACH_KM);
        // Remote technician (Edinburgh) — would fail reach if site had coords
        TechnicianCandidate remote = new TechnicianCandidate(
                id, true, "UTC",
                List.of(new CertificationRecord("ELEC", EVAL_DATE.plusYears(1))),
                List.of(thursdayWindow()), List.of(), 55.95, -3.19);
        EligibilityResult result = filter.filter(List.of(remote), noCoords);

        assertThat(result.eligible()).containsExactly(id); // not excluded as OUT_OF_REACH
        assertThat(result.reachUnknown()).isTrue();
    }

    @Test
    @DisplayName("multiple simultaneous reasons — INACTIVE_TECHNICIAN takes priority")
    void multipleReasons_inactiveTakesPriority() {
        UUID id = UUID.randomUUID();
        // Inactive + missing cert + remote
        TechnicianCandidate candidate = new TechnicianCandidate(
                id, false, "UTC", List.of(), List.of(), List.of(), 55.95, -3.19);
        EligibilityResult result = filter.filter(
                List.of(candidate), requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.INACTIVE_TECHNICIAN);
    }

    @Test
    @DisplayName("empty candidate pool returns empty eligible list — valid non-error outcome")
    void emptyPool_emptyResult() {
        EligibilityResult result = filter.filter(List.of(), requirements(List.of("ELEC")));

        assertThat(result.eligible()).isEmpty();
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    @DisplayName("multiple required certifications — all must be satisfied")
    void multipleRequiredCerts_allMustBeSatisfied() {
        UUID id = UUID.randomUUID();
        // Has ELEC but not GAS
        CertificationRecord elec = new CertificationRecord("ELEC", EVAL_DATE.plusYears(1));
        EligibilityResult result = filter.filter(
                List.of(activeCandidate(id, List.of(elec))),
                requirements(List.of("ELEC", "GAS")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_MISSING);
    }

    @Test
    @DisplayName("multiple candidates — correct split between eligible and excluded")
    void multipleCandidate_correctSplit() {
        UUID eligible1 = UUID.randomUUID();
        UUID eligible2 = UUID.randomUUID();
        UUID excluded1 = UUID.randomUUID(); // inactive
        UUID excluded2 = UUID.randomUUID(); // expired cert

        CertificationRecord validCert   = new CertificationRecord("ELEC", EVAL_DATE.plusYears(1));
        CertificationRecord expiredCert = new CertificationRecord("ELEC", EVAL_DATE.minusDays(1));

        List<TechnicianCandidate> pool = List.of(
                activeCandidate(eligible1, List.of(validCert)),
                activeCandidate(eligible2, List.of(validCert)),
                new TechnicianCandidate(excluded1, false, "UTC", List.of(), List.of(), List.of(), 51.5, -0.1),
                activeCandidate(excluded2, List.of(expiredCert)));

        EligibilityResult result = filter.filter(pool, requirements(List.of("ELEC")));

        assertThat(result.eligible()).containsExactlyInAnyOrder(eligible1, eligible2);
        assertThat(result.excluded()).hasSize(2);
    }

    @Test
    @DisplayName("UNAVAILABLE_IN_WINDOW when shift window does not cover full service window time")
    void shiftWindowTooNarrow_unavailable() {
        UUID id = UUID.randomUUID();
        // Shift ends at 15:00 but service window ends at 16:00
        TechnicianCandidate.AvailabilityWindow narrow = new TechnicianCandidate.AvailabilityWindow(
                4, LocalTime.of(8, 0), LocalTime.of(15, 0),
                LocalDate.of(2025, 1, 1), null);
        TechnicianCandidate candidate = new TechnicianCandidate(
                id, true, "UTC",
                List.of(new CertificationRecord("ELEC", EVAL_DATE.plusYears(1))),
                List.of(narrow), List.of(), 51.5, -0.1);
        EligibilityResult result = filter.filter(
                List.of(candidate), requirements(List.of("ELEC")));

        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }
}

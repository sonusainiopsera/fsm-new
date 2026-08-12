package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link EligibilityFilter}.
 *
 * <p>No Spring context, no database. All time boundaries use a fixed {@link Clock}.
 */
class EligibilityFilterTest {

    // Service window on Monday 2026-08-17 09:00-16:00 UTC
    private static final Instant WINDOW_START = Instant.parse("2026-08-17T09:00:00Z");
    private static final Instant WINDOW_END   = Instant.parse("2026-08-17T16:00:00Z");
    private static final LocalDate EVAL_DATE  = LocalDate.of(2026, 8, 17);

    // Fixed clock at window start for deterministic tests
    private static final Clock FIXED_CLOCK = Clock.fixed(WINDOW_START, ZoneOffset.UTC);

    // Site in London
    private static final double SITE_LAT = 51.5074;
    private static final double SITE_LON = -0.1278;

    // Within 100 km: London (same coords, dist = 0)
    private static final double NEAR_LAT = 51.5074;
    private static final double NEAR_LON = -0.1278;
    // Out of reach: Sydney, ~16 800 km
    private static final double FAR_LAT  = -33.8688;
    private static final double FAR_LON  = 151.2093;

    private final EligibilityFilter filter = new EligibilityFilter(FIXED_CLOCK);

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static TechnicianCandidate eligible(UUID id) {
        return new TechnicianCandidate(
                id, true, "Europe/London", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 12, 31))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
    }

    private static WorkOrderRequirements requirements() {
        return new WorkOrderRequirements(
                Set.of("GAS_SAFE"), WINDOW_START, WINDOW_END, SITE_LAT, SITE_LON, 100.0);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void emptyPool_returnsEmptyResult() {
        EligibilityResult result = filter.filter(List.of(), requirements());
        assertThat(result.eligibleTechnicianIds()).isEmpty();
        assertThat(result.excluded()).isEmpty();
        assertThat(result.reachUnknownCount()).isZero();
    }

    @Test
    void eligibleCandidate_appearsInEligibleList() {
        UUID id = UUID.randomUUID();
        EligibilityResult result = filter.filter(List.of(eligible(id)), requirements());
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void inactiveTechnician_excludedWithInactiveReason() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate inactive = new TechnicianCandidate(
                id, false, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 1, 1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(inactive), requirements());
        assertThat(result.eligibleTechnicianIds()).isEmpty();
        assertThat(result.excluded()).hasSize(1);
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.INACTIVE_TECHNICIAN);
    }

    @Test
    void missingCertification_excludedWithMissingReason() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate noCert = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(),  // no certs
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(noCert), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_MISSING);
    }

    @Test
    void expiredCertification_excludedWithExpiredReason() {
        UUID id = UUID.randomUUID();
        // Expires one day before eval date → strictly before → expired
        TechnicianCandidate expired = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", EVAL_DATE.minusDays(1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(expired), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
    }

    @Test
    void certExpiringOnEvalDate_isEligible() {
        UUID id = UUID.randomUUID();
        // Expires exactly on eval date → inclusive, not expired
        TechnicianCandidate expiresOnDay = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", EVAL_DATE)),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(expiresOnDay), requirements());
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
    }

    @Test
    void certExpiringDayBeforeEvalDate_isExpired() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate c = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", EVAL_DATE.minusDays(1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(c), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
    }

    @Test
    void perpetualCertification_isEligible() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate perpetual = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", null)), // null = perpetual
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(perpetual), requirements());
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
    }

    @Test
    void twoInstancesSameCertOneExpiredOneCurrent_isEligible() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate dual = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(
                        new TechnicianCandidate.CertSnap("GAS_SAFE", EVAL_DATE.minusYears(1)),   // expired
                        new TechnicianCandidate.CertSnap("GAS_SAFE", EVAL_DATE.plusYears(1))     // current
                ),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(dual), requirements());
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
    }

    @Test
    void emptyCertRequirements_noCertExclusions() {
        UUID id = UUID.randomUUID();
        WorkOrderRequirements noCertReq = new WorkOrderRequirements(
                Set.of(), WINDOW_START, WINDOW_END, SITE_LAT, SITE_LON, 100.0);
        TechnicianCandidate noCert = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON, List.of(),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(noCert), noCertReq);
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void absenceDuringWindow_excludedWithUnavailableReason() {
        UUID id = UUID.randomUUID();
        // Absence fully covers the service window
        TechnicianCandidate c = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 1, 1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of(new TechnicianCandidate.AbsenceSnap(
                        Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-18T00:00:00Z"))));
        EligibilityResult result = filter.filter(List.of(c), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    void noShiftWindows_excludedWithUnavailableReason() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate c = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 1, 1))),
                List.of(), // no windows
                List.of());
        EligibilityResult result = filter.filter(List.of(c), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    void windowOnWrongDay_excludedWithUnavailableReason() {
        UUID id = UUID.randomUUID();
        // Service window is Monday; technician only has a Tuesday window
        TechnicianCandidate c = new TechnicianCandidate(
                id, true, "UTC", NEAR_LAT, NEAR_LON,
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 1, 1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(c), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    void outOfReach_excludedWithOutOfReachReason() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate c = new TechnicianCandidate(
                id, true, "UTC", FAR_LAT, FAR_LON,  // Sydney
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 1, 1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(c), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.OUT_OF_REACH);
    }

    @Test
    void siteCoordinatesNull_geoCheckSkipped_candidateEligibleAndCountedAsReachUnknown() {
        UUID id = UUID.randomUUID();
        WorkOrderRequirements noSiteCoords = new WorkOrderRequirements(
                Set.of("GAS_SAFE"), WINDOW_START, WINDOW_END, null, null, 100.0);
        EligibilityResult result = filter.filter(List.of(eligible(id)), noSiteCoords);
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
        assertThat(result.reachUnknownCount()).isEqualTo(1);
    }

    @Test
    void technicianHomeCoordinatesNull_geoCheckSkipped_candidateEligibleAndCountedAsReachUnknown() {
        UUID id = UUID.randomUUID();
        TechnicianCandidate noCoords = new TechnicianCandidate(
                id, true, "UTC", null, null, // no home coords
                List.of(new TechnicianCandidate.CertSnap("GAS_SAFE", LocalDate.of(2099, 1, 1))),
                List.of(new TechnicianCandidate.WindowSnap(
                        DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0),
                        LocalDate.of(2026, 1, 1), null)),
                List.of());
        EligibilityResult result = filter.filter(List.of(noCoords), requirements());
        assertThat(result.eligibleTechnicianIds()).containsExactly(id);
        assertThat(result.reachUnknownCount()).isEqualTo(1);
    }

    @Test
    void inactiveRulePrecedesCertRule() {
        UUID id = UUID.randomUUID();
        // Inactive AND missing cert — should report INACTIVE, not CERT_MISSING
        TechnicianCandidate c = new TechnicianCandidate(
                id, false, "UTC", NEAR_LAT, NEAR_LON,
                List.of(), // no certs either
                List.of(),
                List.of());
        EligibilityResult result = filter.filter(List.of(c), requirements());
        assertThat(result.excluded().get(0).reason()).isEqualTo(ExclusionReason.INACTIVE_TECHNICIAN);
    }

    @Test
    void fullPoolExcluded_returnsEmptyEligibleList() {
        List<TechnicianCandidate> pool = List.of(
                new TechnicianCandidate(UUID.randomUUID(), false, "UTC", null, null, List.of(), List.of(), List.of()),
                new TechnicianCandidate(UUID.randomUUID(), false, "UTC", null, null, List.of(), List.of(), List.of())
        );
        EligibilityResult result = filter.filter(pool, requirements());
        assertThat(result.eligibleTechnicianIds()).isEmpty();
        assertThat(result.excluded()).hasSize(2);
    }

    @Test
    void multipleCandidatesMixed_countsAreCorrect() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID excl = UUID.randomUUID();

        List<TechnicianCandidate> pool = List.of(
                eligible(e1),
                eligible(e2),
                new TechnicianCandidate(excl, true, "UTC", NEAR_LAT, NEAR_LON, List.of(), List.of(), List.of())
        );
        EligibilityResult result = filter.filter(pool, requirements());
        assertThat(result.eligibleTechnicianIds()).containsExactlyInAnyOrder(e1, e2);
        assertThat(result.excluded()).hasSize(1);
    }
}

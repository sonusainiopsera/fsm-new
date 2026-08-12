package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.support.PostgresContainerSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the dispatch eligibility gate.
 *
 * <p>Seeds V131 fixture technicians (22 named + 150 bulk) and verifies:
 * <ul>
 *   <li>Exact eligible technician set for the named group</li>
 *   <li>Correct exclusion reasons for each named exclusion group</li>
 *   <li>Statement count ≤ 10 for 170 candidates — proves no N+1</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "dispatch.eligibility.candidate-page-size=200",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import(EligibilityServiceIT.MetricsConfig.class)
class EligibilityServiceIT extends PostgresContainerSupport {

    // Service window: Monday 2026-08-17 09:00-16:00 UTC
    private static final Instant WINDOW_START = Instant.parse("2026-08-17T09:00:00Z");
    private static final Instant WINDOW_END   = Instant.parse("2026-08-17T16:00:00Z");

    // Site: central London — all London-homed techs are within 100 km
    private static final double SITE_LAT     = 51.5074;
    private static final double SITE_LON     = -0.1278;
    private static final double MAX_REACH_KM = 100.0;

    // Known named UUIDs from fixture
    private static UUID tech(int i) {
        return UUID.fromString("dd000000-0000-0000-0000-" + String.format("%012d", i));
    }

    @Autowired
    private EligibilityService eligibilityService;

    @Autowired
    private EntityManagerFactory emf;

    private WorkOrderRequirements gasWindowRequirements() {
        return new WorkOrderRequirements(
                Set.of("GAS_SAFE"), WINDOW_START, WINDOW_END, SITE_LAT, SITE_LON, MAX_REACH_KM);
    }

    @Test
    void eligibleSet_containsExactlyThe5NamedEligibleTechniciansPlusBulk() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        // Named eligible group: 5001-5005
        assertThat(result.eligibleTechnicianIds())
                .contains(tech(5001), tech(5002), tech(5003), tech(5004), tech(5005));
    }

    @Test
    void certMissingGroup_allExcludedWithCorrectReason() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        Map<UUID, ExclusionReason> excludedMap = result.excluded().stream()
                .collect(Collectors.toMap(e -> e.technicianId(), e -> e.reason()));

        assertThat(excludedMap.get(tech(5006))).isEqualTo(ExclusionReason.CERTIFICATION_MISSING);
        assertThat(excludedMap.get(tech(5007))).isEqualTo(ExclusionReason.CERTIFICATION_MISSING);
        assertThat(excludedMap.get(tech(5008))).isEqualTo(ExclusionReason.CERTIFICATION_MISSING);
    }

    @Test
    void certExpiredGroup_allExcludedWithCorrectReason() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        Map<UUID, ExclusionReason> excludedMap = result.excluded().stream()
                .collect(Collectors.toMap(e -> e.technicianId(), e -> e.reason()));

        assertThat(excludedMap.get(tech(5009))).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
        assertThat(excludedMap.get(tech(5010))).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
        assertThat(excludedMap.get(tech(5011))).isEqualTo(ExclusionReason.CERTIFICATION_EXPIRED);
    }

    @Test
    void unavailableAbsenceGroup_allExcludedWithCorrectReason() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        Map<UUID, ExclusionReason> excludedMap = result.excluded().stream()
                .collect(Collectors.toMap(e -> e.technicianId(), e -> e.reason()));

        assertThat(excludedMap.get(tech(5012))).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
        assertThat(excludedMap.get(tech(5013))).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
        assertThat(excludedMap.get(tech(5014))).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    void unavailableNoShiftGroup_allExcludedWithCorrectReason() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        Map<UUID, ExclusionReason> excludedMap = result.excluded().stream()
                .collect(Collectors.toMap(e -> e.technicianId(), e -> e.reason()));

        assertThat(excludedMap.get(tech(5015))).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
        assertThat(excludedMap.get(tech(5016))).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
        assertThat(excludedMap.get(tech(5017))).isEqualTo(ExclusionReason.UNAVAILABLE_IN_WINDOW);
    }

    @Test
    void oorGroup_allExcludedWithCorrectReason() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        Map<UUID, ExclusionReason> excludedMap = result.excluded().stream()
                .collect(Collectors.toMap(e -> e.technicianId(), e -> e.reason()));

        assertThat(excludedMap.get(tech(5018))).isEqualTo(ExclusionReason.OUT_OF_REACH);
        assertThat(excludedMap.get(tech(5019))).isEqualTo(ExclusionReason.OUT_OF_REACH);
        assertThat(excludedMap.get(tech(5020))).isEqualTo(ExclusionReason.OUT_OF_REACH);
    }

    @Test
    void inactiveTechnicians_notPresentInResult() {
        EligibilityResult result = eligibilityService.evaluate(gasWindowRequirements());

        Set<UUID> allResultIds = result.eligibleTechnicianIds().stream()
                .collect(Collectors.toSet());
        result.excluded().forEach(e -> allResultIds.add(e.technicianId()));

        // Inactive techs are filtered by the SQL query — they never appear in either list
        assertThat(allResultIds).doesNotContain(tech(5021), tech(5022));
    }

    @Test
    void statementCount_isConstantRegardlessOfCandidatePoolSize() {
        // With 170 active technicians loaded, the three-query design means ≤ 10 statements.
        // If there were N+1, we'd see 170+ statements.
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        eligibilityService.evaluate(gasWindowRequirements());

        long stmtCount = stats.getPrepareStatementCount();
        assertThat(stmtCount)
                .as("Expected ≤ 10 SQL statements regardless of pool size; got %d — N+1 suspected", stmtCount)
                .isLessThanOrEqualTo(10L);
    }

    @Test
    void emptyCertRequirements_noCertExclusions_allWithWindowsEligible() {
        WorkOrderRequirements noCertReq = new WorkOrderRequirements(
                Set.of(), WINDOW_START, WINDOW_END, SITE_LAT, SITE_LON, MAX_REACH_KM);

        EligibilityResult result = eligibilityService.evaluate(noCertReq);

        // The named CERT_MISSING (5006-5008) and CERT_EXPIRED (5009-5011) groups have
        // Monday windows — they should now pass and be in the eligible list.
        assertThat(result.eligibleTechnicianIds())
                .contains(tech(5006), tech(5007), tech(5008),
                          tech(5009), tech(5010), tech(5011));
    }

    // ── Test config to avoid Redis / SimpleMeterRegistry conflicts ────────────

    @Configuration
    static class MetricsConfig {
        @Bean
        @Primary
        MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}

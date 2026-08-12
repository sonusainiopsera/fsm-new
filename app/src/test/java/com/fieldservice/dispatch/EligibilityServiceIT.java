package com.fieldservice.dispatch;

import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the eligibility service (WO-133).
 *
 * <p>Seeds the 200-technician dispatch fixture and asserts:
 * <ul>
 *   <li>All 50 valid dispatch-namespace technicians are eligible</li>
 *   <li>All 50 expired-cert technicians are excluded as CERTIFICATION_EXPIRED</li>
 *   <li>All 50 missing-cert technicians are excluded as CERTIFICATION_MISSING</li>
 *   <li>All 50 inactive technicians are excluded as INACTIVE_TECHNICIAN</li>
 *   <li>Reach filter works and reachUnknown flag set when site has no coordinates</li>
 * </ul>
 *
 * <p>Uses dispatch-namespace UUID prefix {@code 00000000-0000-7033-0011-*} to identify
 * fixture rows among other seed data in the shared test database.
 *
 * <p>Requires Docker (tag: integration).
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/fixtures/seed-dispatch-candidates.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class EligibilityServiceIT {

    // Thursday 2026-01-15 08:00–16:00 UTC — matches Thursday shift windows in fixture
    private static final Instant WINDOW_START = Instant.parse("2026-01-15T08:00:00Z");
    private static final Instant WINDOW_END   = Instant.parse("2026-01-15T16:00:00Z");

    // Central London — home-base sites in the fixture are London (~51.53, -0.10)
    // so all active-eligible dispatch technicians are within 50 km
    private static final double SITE_LAT     = 51.50;
    private static final double SITE_LON     = -0.10;
    private static final double MAX_REACH_KM = 50.0;

    /** UUID prefix for dispatch-fixture technicians (IDs 1–200). */
    private static final String DISPATCH_UUID_PREFIX = "00000000-0000-7033-0011-";

    @Autowired
    private EligibilityService eligibilityService;

    @TestConfiguration
    static class MetricsConfig {
        @Bean
        @Primary
        MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** Extracts dispatch-namespace technician IDs from the result set. */
    private static List<UUID> dispatchEligible(EligibilityResult result) {
        return result.eligible().stream()
                .filter(id -> id.toString().startsWith(DISPATCH_UUID_PREFIX))
                .toList();
    }

    private static Map<ExclusionReason, Long> dispatchExcluded(EligibilityResult result) {
        return result.excluded().stream()
                .filter(e -> e.technicianId().toString().startsWith(DISPATCH_UUID_PREFIX))
                .collect(Collectors.groupingBy(
                        com.fieldservice.dispatch.api.ExcludedCandidate::reason,
                        Collectors.counting()));
    }

    @Test
    @DisplayName("eligible set contains all 50 valid dispatch fixture technicians")
    void eligibleSet_containsAllValidDispatchTechnicians() {
        WorkOrderRequirements requirements = new WorkOrderRequirements(
                List.of("ELEC_DISPATCH"),
                WINDOW_START, WINDOW_END,
                SITE_LAT, SITE_LON, MAX_REACH_KM);

        EligibilityResult result = eligibilityService.evaluate(requirements);

        assertThat(dispatchEligible(result)).hasSize(50);
        assertThat(result.reachUnknown()).isFalse();
    }

    @Test
    @DisplayName("dispatch excluded set has correct reason distribution")
    void excludedReasonDistribution_matchesFixture() {
        WorkOrderRequirements requirements = new WorkOrderRequirements(
                List.of("ELEC_DISPATCH"),
                WINDOW_START, WINDOW_END,
                SITE_LAT, SITE_LON, MAX_REACH_KM);

        EligibilityResult result = eligibilityService.evaluate(requirements);

        Map<ExclusionReason, Long> byReason = dispatchExcluded(result);

        assertThat(byReason.getOrDefault(ExclusionReason.CERTIFICATION_EXPIRED, 0L)).isEqualTo(50L);
        assertThat(byReason.getOrDefault(ExclusionReason.CERTIFICATION_MISSING, 0L)).isEqualTo(50L);
        assertThat(byReason.getOrDefault(ExclusionReason.INACTIVE_TECHNICIAN, 0L)).isEqualTo(50L);
    }

    @Test
    @DisplayName("site with no coordinates — reach filter skipped, reachUnknown=true")
    void noSiteCoords_reachFilterSkipped() {
        WorkOrderRequirements requirements = new WorkOrderRequirements(
                List.of("ELEC_DISPATCH"),
                WINDOW_START, WINDOW_END,
                null, null, MAX_REACH_KM);

        EligibilityResult result = eligibilityService.evaluate(requirements);

        assertThat(result.reachUnknown()).isTrue();
        // No OUT_OF_REACH exclusions when site coords are missing
        assertThat(result.excluded()).noneMatch(
                e -> e.reason() == ExclusionReason.OUT_OF_REACH);
    }

    @Test
    @DisplayName("empty required-cert set — all 50 inactive dispatch technicians still excluded")
    void emptyCertRequirements_inactiveTechniciansStillExcluded() {
        WorkOrderRequirements requirements = new WorkOrderRequirements(
                List.of(), // no cert required
                WINDOW_START, WINDOW_END,
                SITE_LAT, SITE_LON, MAX_REACH_KM);

        EligibilityResult result = eligibilityService.evaluate(requirements);

        long dispatchInactive = result.excluded().stream()
                .filter(e -> e.technicianId().toString().startsWith(DISPATCH_UUID_PREFIX))
                .filter(e -> e.reason() == ExclusionReason.INACTIVE_TECHNICIAN)
                .count();
        assertThat(dispatchInactive).isEqualTo(50L);
    }
}

package com.fieldservice.sla.internal;

import com.fieldservice.domain.sla.SlaPolicy;
import com.fieldservice.sla.SlaDeadlines;
import com.fieldservice.sla.SlaPolicyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlaPolicyService#calculate(String, Instant)} deadline arithmetic.
 *
 * Tests verify that given a resolved SLA policy, the response deadline, resolution deadline,
 * and at-risk instant are computed correctly per the formula:
 * <ul>
 *   <li>responseDueAt = createdAt + responseMinutes</li>
 *   <li>resolutionDueAt = createdAt + resolutionMinutes</li>
 *   <li>atRiskAt = createdAt + floor(resolutionMinutes * atRiskFraction)</li>
 * </ul>
 */
@DisplayName("SlaPolicyService — deadline arithmetic")
class SlaPolicyServiceDeadlineTest {

    private static final Instant FIXED_NOW = Instant.parse("2024-06-15T10:00:00Z");

    private SlaPolicyRepository policyRepo;
    private SlaClockPauseRepository pauseRepo;
    private SlaPolicyService service;

    @BeforeEach
    void setUp() {
        policyRepo = mock(SlaPolicyRepository.class);
        pauseRepo = mock(SlaClockPauseRepository.class);
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new SlaPolicyService(policyRepo, pauseRepo, fixedClock, new SimpleMeterRegistry());
    }

    // ── Response and resolution deadlines ────────────────────────────────────

    @Test
    @DisplayName("LOW priority (response=240min, resolution=1440min, atRisk=0.80) computes correct deadlines")
    void calculate_lowPriority_correctDeadlines() {
        SlaPolicy policy = policyFor("LOW", 240, 1440, new BigDecimal("0.80"));
        when(policyRepo.findActiveForPriorityAt(eq("LOW"), any())).thenReturn(List.of(policy));

        SlaDeadlines deadlines = service.calculate("LOW", FIXED_NOW);

        assertThat(deadlines.responseDueAt())
                .as("responseDueAt must be 240 minutes after createdAt")
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(240)));

        assertThat(deadlines.resolutionDueAt())
                .as("resolutionDueAt must be 1440 minutes after createdAt")
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(1440)));

        // atRiskAt = 1440 * 0.80 = 1152 minutes
        assertThat(deadlines.atRiskAt())
                .as("atRiskAt must be 1152 minutes (floor(1440*0.80)) after createdAt")
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(1152)));
    }

    @Test
    @DisplayName("MEDIUM priority (response=120min, resolution=480min, atRisk=0.75) computes correct deadlines")
    void calculate_mediumPriority_correctDeadlines() {
        SlaPolicy policy = policyFor("MEDIUM", 120, 480, new BigDecimal("0.75"));
        when(policyRepo.findActiveForPriorityAt(eq("MEDIUM"), any())).thenReturn(List.of(policy));

        SlaDeadlines deadlines = service.calculate("MEDIUM", FIXED_NOW);

        assertThat(deadlines.responseDueAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(120)));
        assertThat(deadlines.resolutionDueAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(480)));
        // atRiskAt = floor(480 * 0.75) = 360 minutes
        assertThat(deadlines.atRiskAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(360)));
    }

    @Test
    @DisplayName("HIGH priority (response=60min, resolution=240min, atRisk=0.75) computes correct deadlines")
    void calculate_highPriority_correctDeadlines() {
        SlaPolicy policy = policyFor("HIGH", 60, 240, new BigDecimal("0.75"));
        when(policyRepo.findActiveForPriorityAt(eq("HIGH"), any())).thenReturn(List.of(policy));

        SlaDeadlines deadlines = service.calculate("HIGH", FIXED_NOW);

        assertThat(deadlines.responseDueAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(60)));
        assertThat(deadlines.resolutionDueAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(240)));
        // atRiskAt = floor(240 * 0.75) = 180 minutes
        assertThat(deadlines.atRiskAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(180)));
    }

    @Test
    @DisplayName("CRITICAL priority (response=15min, resolution=60min, atRisk=0.50) computes correct deadlines")
    void calculate_criticalPriority_correctDeadlines() {
        SlaPolicy policy = policyFor("CRITICAL", 15, 60, new BigDecimal("0.50"));
        when(policyRepo.findActiveForPriorityAt(eq("CRITICAL"), any())).thenReturn(List.of(policy));

        SlaDeadlines deadlines = service.calculate("CRITICAL", FIXED_NOW);

        assertThat(deadlines.responseDueAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));
        assertThat(deadlines.resolutionDueAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(60)));
        // atRiskAt = floor(60 * 0.50) = 30 minutes
        assertThat(deadlines.atRiskAt())
                .isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(30)));
    }

    // ── atRiskAt is always before or equal to resolutionDueAt ────────────────

    @Test
    @DisplayName("atRiskAt is strictly before resolutionDueAt when fraction < 1.0")
    void calculate_atRiskAt_isBeforeResolutionDueAt() {
        SlaPolicy policy = policyFor("HIGH", 60, 240, new BigDecimal("0.75"));
        when(policyRepo.findActiveForPriorityAt(eq("HIGH"), any())).thenReturn(List.of(policy));

        SlaDeadlines deadlines = service.calculate("HIGH", FIXED_NOW);

        assertThat(deadlines.atRiskAt())
                .as("atRiskAt must be before resolutionDueAt")
                .isBefore(deadlines.resolutionDueAt());
        assertThat(deadlines.atRiskAt())
                .as("atRiskAt must be before or equal to resolutionDueAt")
                .isBeforeOrEqualTo(deadlines.resolutionDueAt());
    }

    @Test
    @DisplayName("responseDueAt is always before or equal to resolutionDueAt")
    void calculate_responseDueAt_isBeforeResolutionDueAt() {
        SlaPolicy policy = policyFor("HIGH", 60, 240, new BigDecimal("0.75"));
        when(policyRepo.findActiveForPriorityAt(eq("HIGH"), any())).thenReturn(List.of(policy));

        SlaDeadlines deadlines = service.calculate("HIGH", FIXED_NOW);

        assertThat(deadlines.responseDueAt())
                .as("responseDueAt must be before or equal to resolutionDueAt")
                .isBeforeOrEqualTo(deadlines.resolutionDueAt());
    }

    // ── Missing policy → exception ───────────────────────────────────────────

    @Test
    @DisplayName("No active policy for priority → SlaPolicyUnavailableException")
    void calculate_noPolicyFound_throwsSlaPolicyUnavailableException() {
        when(policyRepo.findActiveForPriorityAt(any(), any())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.calculate("UNKNOWN", FIXED_NOW))
                .isInstanceOf(SlaPolicyUnavailableException.class);
    }

    // ── effectiveResolutionDueAt: pause accrual ───────────────────────────────

    @Test
    @DisplayName("effectiveResolutionDueAt with no pauses returns original resolutionDueAt")
    void effectiveResolutionDueAt_noPauses_returnsOriginal() {
        UUID workOrderId = UUID.randomUUID();
        Instant resolutionDueAt = FIXED_NOW.plus(Duration.ofHours(4));
        when(pauseRepo.findByWorkOrderId(workOrderId)).thenReturn(Collections.emptyList());

        Instant effective = service.effectiveResolutionDueAt(workOrderId, resolutionDueAt);

        assertThat(effective).isEqualTo(resolutionDueAt);
    }

    @Test
    @DisplayName("effectiveResolutionDueAt with closed pause extends deadline by pause duration")
    void effectiveResolutionDueAt_withClosedPause_extendsDeadline() {
        UUID workOrderId = UUID.randomUUID();
        Instant resolutionDueAt = FIXED_NOW.plus(Duration.ofHours(4));

        Instant pausedAt = FIXED_NOW.minus(Duration.ofHours(2));
        Instant resumedAt = FIXED_NOW.minus(Duration.ofHours(1));
        SlaClockPause pause = SlaClockPause.open(workOrderId, "WAITING_FOR_PARTS", pausedAt);
        pause.resume(resumedAt);

        when(pauseRepo.findByWorkOrderId(workOrderId)).thenReturn(List.of(pause));

        Instant effective = service.effectiveResolutionDueAt(workOrderId, resolutionDueAt);

        assertThat(effective)
                .as("Deadline extended by 1 hour pause duration")
                .isEqualTo(resolutionDueAt.plus(Duration.ofHours(1)));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private SlaPolicy policyFor(String priority, int responseMinutes, int resolutionMinutes,
                                 BigDecimal atRiskFraction) {
        SlaPolicy p = new SlaPolicy();
        p.setPriority(priority);
        p.setResponseMinutes(responseMinutes);
        p.setResolutionMinutes(resolutionMinutes);
        p.setAtRiskFraction(atRiskFraction);
        p.setEffectiveFrom(Instant.EPOCH);
        p.setEffectiveTo(null);
        p.setActive(true);
        return p;
    }
}

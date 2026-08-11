package com.fieldservice.workorder.application;

import com.fieldservice.sla.SlaDeadlineResult;
import com.fieldservice.sla.SlaPolicyUnavailableException;
import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.sla.internal.SlaClockPauseRepository;
import com.fieldservice.sla.internal.SlaPolicyRepository;
import com.fieldservice.sla.internal.SlaPolicyService;
import com.fieldservice.workorder.domain.WorkOrderPriority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SLA deadline derivation per priority tier (AC10).
 * Uses a pinned Clock instant to verify exact arithmetic.
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderCreationDeadlineTest {

    @Mock SlaPolicyRepository     policyRepo;
    @Mock SlaClockPauseRepository pauseRepo;

    SlaPolicyService slaService;

    static final Instant PINNED = Instant.parse("2025-09-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        slaService = new SlaPolicyService(policyRepo, pauseRepo, new SimpleMeterRegistry());
    }

    @ParameterizedTest(name = "{0}: response={1}m, resolution={2}m, atRiskFraction={3} → atRisk={4}m")
    @CsvSource({
        "LOW,     240, 480, 0.80, 384",
        "MEDIUM,  120, 240, 0.80, 192",
        "HIGH,     60, 120, 0.80,  96",
        "CRITICAL, 30,  60, 0.80,  48",
    })
    @DisplayName("AC10: deadline arithmetic correct for every seeded priority tier")
    void deadlineArithmeticPerPriority(String priority, int responseMin, int resolutionMin,
                                       double atRiskFraction, int expectedAtRiskMin) {
        SlaPolicy policy = new SlaPolicy(priority, responseMin, resolutionMin,
                BigDecimal.valueOf(atRiskFraction), PINNED.minusSeconds(3600));
        when(policyRepo.findActiveByPriorityAt(eq(priority), any()))
                .thenReturn(Optional.of(policy));

        SlaDeadlineResult result = slaService.calculate(priority, PINNED);

        assertThat(result.responseDueAt())
                .isEqualTo(PINNED.plus(Duration.ofMinutes(responseMin)));
        assertThat(result.resolutionDueAt())
                .isEqualTo(PINNED.plus(Duration.ofMinutes(resolutionMin)));
        assertThat(result.atRiskAt())
                .isEqualTo(PINNED.plus(Duration.ofMinutes(expectedAtRiskMin)));
    }

    @Test
    @DisplayName("AC10: at-risk threshold uses fractional rounding — 50% of 100m = 50m")
    void atRiskFractionRounding() {
        SlaPolicy policy = new SlaPolicy("HIGH", 30, 100,
                BigDecimal.valueOf(0.50), PINNED.minusSeconds(3600));
        when(policyRepo.findActiveByPriorityAt(eq("HIGH"), any()))
                .thenReturn(Optional.of(policy));

        SlaDeadlineResult result = slaService.calculate("HIGH", PINNED);

        assertThat(result.atRiskAt()).isEqualTo(PINNED.plus(Duration.ofMinutes(50)));
    }

    @Test
    @DisplayName("AC3/AC10: missing active policy throws SlaPolicyUnavailableException")
    void noActivePolicy_throwsUnavailable() {
        when(policyRepo.findActiveByPriorityAt(eq("HIGH"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> slaService.calculate("HIGH", PINNED))
                .isInstanceOf(SlaPolicyUnavailableException.class)
                .extracting("priority").isEqualTo("HIGH");
    }

    @Test
    @DisplayName("AC10: WorkOrderPriority enum covers all four DB constraint values")
    void workOrderPriorityEnumCoversAllTiers() {
        assertThat(WorkOrderPriority.values()).hasSize(4);
        assertThat(WorkOrderPriority.LOW.toDbValue()).isEqualTo("LOW");
        assertThat(WorkOrderPriority.MEDIUM.toDbValue()).isEqualTo("MEDIUM");
        assertThat(WorkOrderPriority.HIGH.toDbValue()).isEqualTo("HIGH");
        assertThat(WorkOrderPriority.CRITICAL.toDbValue()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("AC5: CUSTOMER portal priority ceiling is HIGH (not CRITICAL)")
    void portalPriorityCeiling_isHigh() {
        assertThat(WorkOrderCreationService.PORTAL_PRIORITY_CEILING)
                .isEqualTo(WorkOrderPriority.HIGH);
    }
}

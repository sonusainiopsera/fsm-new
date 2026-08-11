package com.fieldservice.sla;

import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.sla.internal.SlaClockPause;
import com.fieldservice.sla.internal.SlaClockPauseRepository;
import com.fieldservice.sla.internal.SlaPolicyRepository;
import com.fieldservice.sla.internal.SlaPolicyService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaDeadlineCalculatorTest {

    @Mock SlaPolicyRepository    policyRepo;
    @Mock SlaClockPauseRepository pauseRepo;

    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    SlaPolicyService service;

    @BeforeEach
    void setUp() {
        service = new SlaPolicyService(policyRepo, pauseRepo, meterRegistry);
    }

    private SlaPolicy policy(int responseMin, int resolutionMin, double atRisk) {
        return new SlaPolicy("HIGH", responseMin, resolutionMin,
                BigDecimal.valueOf(atRisk), Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Nested
    @DisplayName("calculate()")
    class Calculate {

        @Test
        @DisplayName("stamps response, resolution, and at-risk deadlines correctly")
        void stampsDeadlines() {
            Instant createdAt = Instant.parse("2025-06-01T10:00:00Z");
            when(policyRepo.findActiveByPriorityAt(eq("HIGH"), any()))
                    .thenReturn(Optional.of(policy(60, 240, 0.80)));

            SlaDeadlineResult result = service.calculate("HIGH", createdAt);

            assertThat(result.responseDueAt()).isEqualTo(createdAt.plus(Duration.ofMinutes(60)));
            assertThat(result.resolutionDueAt()).isEqualTo(createdAt.plus(Duration.ofMinutes(240)));
            // at-risk = 80% of 240 = 192 minutes
            assertThat(result.atRiskAt()).isEqualTo(createdAt.plus(Duration.ofMinutes(192)));
            assertThat(result.effectiveResolutionDueAt()).isEqualTo(result.resolutionDueAt());
        }

        @Test
        @DisplayName("throws SlaPolicyUnavailableException and increments counter when no policy")
        void throwsWhenNoPolicyFound() {
            when(policyRepo.findActiveByPriorityAt(eq("ULTRA"), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.calculate("ULTRA", Instant.now()))
                    .isInstanceOf(SlaPolicyUnavailableException.class)
                    .extracting("priority").isEqualTo("ULTRA");

            double failures = meterRegistry.counter("sla_policy_resolution_failures_total").count();
            assertThat(failures).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("effectiveResolutionDeadline()")
    class EffectiveResolution {

        private UUID workOrderId = UUID.randomUUID();

        @Test
        @DisplayName("returns nominal deadline when no pause rows exist")
        void noPauses() {
            when(pauseRepo.findByWorkOrderId(workOrderId)).thenReturn(List.of());
            Instant nominal = Instant.parse("2025-06-01T14:00:00Z");

            Instant effective = service.effectiveResolutionDeadline(
                    workOrderId, nominal, Instant.now());

            assertThat(effective).isEqualTo(nominal);
        }

        @Test
        @DisplayName("adds completed pause duration to nominal deadline")
        void completedPause() throws Exception {
            Instant pausedAt   = Instant.parse("2025-06-01T11:00:00Z");
            Instant resumedAt  = Instant.parse("2025-06-01T12:00:00Z"); // 1 hour pause
            Instant nominal    = Instant.parse("2025-06-01T14:00:00Z");

            SlaClockPause pause = makePause(workOrderId, pausedAt, resumedAt);
            when(pauseRepo.findByWorkOrderId(workOrderId)).thenReturn(List.of(pause));

            Instant effective = service.effectiveResolutionDeadline(
                    workOrderId, nominal, Instant.now());

            assertThat(effective).isEqualTo(nominal.plus(Duration.ofHours(1)));
        }

        @Test
        @DisplayName("accrues open pause up to evaluatedAt")
        void openPause() throws Exception {
            Instant pausedAt    = Instant.parse("2025-06-01T11:00:00Z");
            Instant evaluatedAt = Instant.parse("2025-06-01T12:30:00Z"); // 1.5h open
            Instant nominal     = Instant.parse("2025-06-01T14:00:00Z");

            SlaClockPause openPause = makePause(workOrderId, pausedAt, null);
            when(pauseRepo.findByWorkOrderId(workOrderId)).thenReturn(List.of(openPause));

            Instant effective = service.effectiveResolutionDeadline(
                    workOrderId, nominal, evaluatedAt);

            assertThat(effective).isEqualTo(nominal.plus(Duration.ofMinutes(90)));
        }
    }

    private SlaClockPause makePause(UUID workOrderId, Instant pausedAt, Instant resumedAt)
            throws Exception {
        // SlaClockPause is package-private; use reflection to construct
        java.lang.reflect.Constructor<SlaClockPause> ctor =
                SlaClockPause.class.getDeclaredConstructor(UUID.class, String.class, Instant.class);
        ctor.setAccessible(true);
        SlaClockPause p = ctor.newInstance(workOrderId, "WEATHER", pausedAt);
        if (resumedAt != null) {
            java.lang.reflect.Method m = SlaClockPause.class.getDeclaredMethod("resume", Instant.class);
            m.setAccessible(true);
            m.invoke(p, resumedAt);
        }
        return p;
    }
}

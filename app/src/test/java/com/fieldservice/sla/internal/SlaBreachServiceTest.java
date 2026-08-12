package com.fieldservice.sla.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.sla.SlaBreachReasonCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for SlaBreachService.
 *
 * <p>No Spring context — all dependencies are mocked.
 * Fixed Clock so computations are deterministic.
 */
class SlaBreachServiceTest {

    static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");
    static final Clock   CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    SlaBreachRepository   breachRepo;
    SlaRiskFlagRepository flagRepo;
    DomainEventPublisher  eventPublisher;
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    SlaBreachService      service;

    @BeforeEach
    void setUp() {
        breachRepo     = mock(SlaBreachRepository.class);
        flagRepo       = mock(SlaRiskFlagRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        jdbc           = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        service        = new SlaBreachService(breachRepo, flagRepo, eventPublisher, jdbc, CLOCK);
    }

    // ─── recordBreach ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recordBreach")
    class RecordBreach {

        @Test
        @DisplayName("persists breach, clears open flags, publishes event")
        void persistsBreachClearsFlagsPublishesEvent() {
            UUID workOrderId   = UUID.randomUUID();
            Instant deadline   = NOW.minusSeconds(600);  // 10 min overrun
            long overrunMins   = 10L;
            long pausedMins    = 5L;

            when(breachRepo.findByWorkOrderIdAndBreachType(workOrderId, "RESOLUTION"))
                    .thenReturn(Optional.empty());

            SlaRiskFlagEntity openFlag = openFlag(workOrderId);
            when(flagRepo.findByWorkOrderIdAndClearedAtIsNull(workOrderId))
                    .thenReturn(List.of(openFlag));

            service.recordBreach(workOrderId, "RESOLUTION", deadline, NOW, overrunMins, pausedMins);

            // Saved breach
            ArgumentCaptor<SlaBreachEntity> breachCaptor = ArgumentCaptor.forClass(SlaBreachEntity.class);
            verify(breachRepo).save(breachCaptor.capture());
            SlaBreachEntity saved = breachCaptor.getValue();
            assertThat(saved.getWorkOrderId()).isEqualTo(workOrderId);
            assertThat(saved.getBreachType()).isEqualTo("RESOLUTION");
            assertThat(saved.getOverrunMinutes()).isEqualTo(10L);
            assertThat(saved.getPausedMinutesExcluded()).isEqualTo(5L);
            assertThat(saved.getReasonCode()).isNull(); // unattributed initially

            // Cleared flag
            verify(flagRepo).save(openFlag);
            assertThat(openFlag.getClearReason()).isEqualTo("BREACHED");

            // Published event
            ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().eventType()).isEqualTo("SlaBreached");
        }

        @Test
        @DisplayName("idempotent: no-op if breach already exists for same type")
        void idempotentIfAlreadyExists() {
            UUID workOrderId = UUID.randomUUID();
            SlaBreachEntity existing = SlaBreachEntity.create(workOrderId, "RESOLUTION",
                    NOW.minusSeconds(3600), NOW, 60L, 0L);

            when(breachRepo.findByWorkOrderIdAndBreachType(workOrderId, "RESOLUTION"))
                    .thenReturn(Optional.of(existing));

            service.recordBreach(workOrderId, "RESOLUTION", NOW.minusSeconds(3600), NOW, 60L, 0L);

            verify(breachRepo, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("negative overrun is clamped to zero")
        void negativeOverrunClampedToZero() {
            UUID workOrderId = UUID.randomUUID();
            Instant deadline = NOW.plusSeconds(600); // deadline still in future — anomaly

            when(breachRepo.findByWorkOrderIdAndBreachType(workOrderId, "RESPONSE"))
                    .thenReturn(Optional.empty());
            when(flagRepo.findByWorkOrderIdAndClearedAtIsNull(workOrderId)).thenReturn(List.of());

            service.recordBreach(workOrderId, "RESPONSE", deadline, NOW, -30L, 0L);

            ArgumentCaptor<SlaBreachEntity> captor = ArgumentCaptor.forClass(SlaBreachEntity.class);
            verify(breachRepo).save(captor.capture());
            assertThat(captor.getValue().getOverrunMinutes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("RESPONSE and RESOLUTION breaches are recorded independently")
        void responseAndResolutionIndependent() {
            UUID workOrderId = UUID.randomUUID();

            when(breachRepo.findByWorkOrderIdAndBreachType(workOrderId, "RESPONSE"))
                    .thenReturn(Optional.empty());
            when(breachRepo.findByWorkOrderIdAndBreachType(workOrderId, "RESOLUTION"))
                    .thenReturn(Optional.empty());
            when(flagRepo.findByWorkOrderIdAndClearedAtIsNull(workOrderId))
                    .thenReturn(List.of());

            service.recordBreach(workOrderId, "RESPONSE", NOW.minusSeconds(1800), NOW, 30L, 0L);
            service.recordBreach(workOrderId, "RESOLUTION", NOW.minusSeconds(600), NOW, 10L, 0L);

            verify(breachRepo, times(2)).save(any(SlaBreachEntity.class));
            verify(eventPublisher, times(2)).publish(any(DomainEvent.class));
        }
    }

    // ─── finalise ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("finalise")
    class Finalise {

        @Test
        @DisplayName("writes finalOverrunMinutes from closure instant")
        void writesFinalOverrun() {
            UUID workOrderId     = UUID.randomUUID();
            Instant deadline     = NOW.minusSeconds(3600);  // 60 min before closure
            Instant closureTime  = NOW;
            SlaBreachEntity breach = SlaBreachEntity.create(
                    workOrderId, "RESOLUTION", deadline, deadline.plusSeconds(300), 5L, 0L);

            when(breachRepo.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                    .thenReturn(List.of(breach));

            service.finalise(workOrderId, closureTime);

            assertThat(breach.getFinalOverrunMinutes()).isEqualTo(60L);
            verify(breachRepo).save(breach);
        }

        @Test
        @DisplayName("finalisation is idempotent — already-finalised breaches are skipped")
        void idempotentFinalisation() {
            UUID workOrderId = UUID.randomUUID();

            // No unfinalised breaches
            when(breachRepo.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                    .thenReturn(List.of());

            service.finalise(workOrderId, NOW);

            verify(breachRepo, never()).save(any());
        }

        @Test
        @DisplayName("finalOverrunMinutes >= overrunMinutes when work order stayed open")
        void finalOverrunGreaterThanOrEqualToDetectionOverrun() {
            UUID workOrderId = UUID.randomUUID();
            Instant deadline = NOW.minusSeconds(3600);  // 60 min ago
            // Detected at NOW - 1800 → 30 min overrun at detection
            Instant detectedAt = NOW.minusSeconds(1800);
            SlaBreachEntity breach = SlaBreachEntity.create(
                    workOrderId, "RESOLUTION", deadline, detectedAt, 30L, 0L);

            // Closure is 1 hour later → 60 min final overrun
            Instant closureAt = NOW;
            when(breachRepo.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                    .thenReturn(List.of(breach));

            service.finalise(workOrderId, closureAt);

            assertThat(breach.getFinalOverrunMinutes()).isGreaterThanOrEqualTo(30L);
        }
    }

    // ─── attribute ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("attribute")
    class Attribute {

        @Test
        @DisplayName("sets reason code and returns updated breach")
        void setsReasonCode() {
            UUID breachId    = UUID.randomUUID();
            UUID workOrderId = UUID.randomUUID();
            SlaBreachEntity breach = SlaBreachEntity.create(
                    workOrderId, "RESOLUTION", NOW.minusSeconds(600), NOW, 10L, 0L);

            when(breachRepo.findById(breachId)).thenReturn(Optional.of(breach));
            when(breachRepo.save(breach)).thenReturn(breach);

            Optional<SlaBreachEntity> result = service.attribute(
                    breachId, SlaBreachReasonCode.PARTS_UNAVAILABLE, "Parts on back-order", UUID.randomUUID());

            assertThat(result).isPresent();
            assertThat(result.get().getReasonCode()).isEqualTo(SlaBreachReasonCode.PARTS_UNAVAILABLE);
            assertThat(result.get().getReasonNote()).isEqualTo("Parts on back-order");
            assertThat(result.get().getAttributedAt()).isEqualTo(NOW); // from fixed clock
        }

        @Test
        @DisplayName("returns empty Optional when breach not found — controller returns 403")
        void returnsEmptyWhenNotFound() {
            when(breachRepo.findById(any())).thenReturn(Optional.empty());

            Optional<SlaBreachEntity> result = service.attribute(
                    UUID.randomUUID(), SlaBreachReasonCode.CAPACITY_SHORTFALL, null, UUID.randomUUID());

            assertThat(result).isEmpty();
        }
    }

    // ─── evaluateBreaches (integration with evaluator) ───────────────────────

    @Nested
    @DisplayName("evaluateBreaches (via SlaRiskEvaluator)")
    class EvaluateBreaches {

        SlaRiskEvaluator evaluator = new SlaRiskEvaluator(CLOCK);

        @Test
        @DisplayName("returns empty list when not breached")
        void emptyWhenHealthy() {
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot = snapshot("IN_PROGRESS",
                    NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW.minusSeconds(60), 0, false);

            assertThat(evaluator.evaluateBreaches(snapshot, NOW)).isEmpty();
        }

        @Test
        @DisplayName("returns RESOLUTION breach when resolution deadline passed")
        void resolutionBreach() {
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot = snapshot("IN_PROGRESS",
                    NOW.minusSeconds(600), null, NOW.minusSeconds(1800), 0, false);

            List<SlaRiskEvaluator.BreachDecision> breaches = evaluator.evaluateBreaches(snapshot, NOW);

            assertThat(breaches).hasSize(1);
            assertThat(breaches.get(0).breachType()).isEqualTo("RESOLUTION");
            assertThat(breaches.get(0).overrunMinutes()).isEqualTo(10L);
        }

        @Test
        @DisplayName("returns RESPONSE breach when response deadline passed but resolution not yet")
        void responseBreach() {
            // Response deadline: 5 min ago; Resolution deadline: 10 min in future
            Instant responseDeadline   = NOW.minusSeconds(300);
            Instant resolutionDeadline = NOW.plusSeconds(600);
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot =
                    new SlaRiskEvaluator.WorkOrderRiskSnapshot(
                            UUID.randomUUID(), "ASSIGNED", NOW.minusSeconds(7200),
                            resolutionDeadline, responseDeadline,
                            NOW.minusSeconds(1800), 0, false, null, null);

            List<SlaRiskEvaluator.BreachDecision> breaches = evaluator.evaluateBreaches(snapshot, NOW);

            assertThat(breaches).hasSize(1);
            assertThat(breaches.get(0).breachType()).isEqualTo("RESPONSE");
            assertThat(breaches.get(0).overrunMinutes()).isEqualTo(5L);
        }

        @Test
        @DisplayName("returns both RESPONSE and RESOLUTION when both deadlines passed")
        void bothBreaches() {
            // Both deadlines in the past
            Instant responseDeadline   = NOW.minusSeconds(1800);  // 30 min ago
            Instant resolutionDeadline = NOW.minusSeconds(600);   // 10 min ago
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot =
                    new SlaRiskEvaluator.WorkOrderRiskSnapshot(
                            UUID.randomUUID(), "IN_PROGRESS", NOW.minusSeconds(86400),
                            resolutionDeadline, responseDeadline,
                            NOW.minusSeconds(3600), 0, false, null, null);

            List<SlaRiskEvaluator.BreachDecision> breaches = evaluator.evaluateBreaches(snapshot, NOW);

            assertThat(breaches).hasSize(2);
            assertThat(breaches.stream().map(SlaRiskEvaluator.BreachDecision::breachType))
                    .containsExactly("RESPONSE", "RESOLUTION");
        }

        @Test
        @DisplayName("AC-8: pause-aware effective deadline — raw elapsed exceeds deadline but effective does not")
        void pauseAwareDeadline() {
            // Raw resolution deadline is 5 min ago, but 30 min of pauses → effective deadline is 25 min in future
            Instant rawDeadline       = NOW.minusSeconds(300);  // 5 min ago
            int     pausedMinutes     = 30;
            // effectiveResolutionDue = rawDeadline + 30 min = NOW + 25 min (still in future)
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot = new SlaRiskEvaluator.WorkOrderRiskSnapshot(
                    UUID.randomUUID(), "IN_PROGRESS", NOW.minusSeconds(7200),
                    rawDeadline, null,
                    NOW.plusSeconds(pausedMinutes * 60 / 2), pausedMinutes, false, null, null);

            List<SlaRiskEvaluator.BreachDecision> breaches = evaluator.evaluateBreaches(snapshot, NOW);

            assertThat(breaches).isEmpty(); // effective deadline is still in the future
        }

        @Test
        @DisplayName("no breach while paused (ON_HOLD)")
        void noBReachWhilePaused() {
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot = snapshot("ON_HOLD",
                    NOW.minusSeconds(600), null, NOW.minusSeconds(1800), 0, true);

            assertThat(evaluator.evaluateBreaches(snapshot, NOW)).isEmpty();
        }

        @Test
        @DisplayName("no breach for terminal state")
        void noBReachForTerminal() {
            SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot = snapshot("COMPLETED",
                    NOW.minusSeconds(600), null, NOW.minusSeconds(3600), 0, false);

            assertThat(evaluator.evaluateBreaches(snapshot, NOW)).isEmpty();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static SlaRiskEvaluator.WorkOrderRiskSnapshot snapshot(
            String state, Instant resolutionDue, Instant responseDeadline,
            Instant atRiskAt, int cumulativeHoldMin, boolean paused) {
        return new SlaRiskEvaluator.WorkOrderRiskSnapshot(
                UUID.randomUUID(), state, NOW.minusSeconds(86400),
                resolutionDue, responseDeadline, atRiskAt, cumulativeHoldMin, paused, null, null);
    }

    private static SlaRiskFlagEntity openFlag(UUID workOrderId) {
        return SlaRiskFlagEntity.raise(workOrderId, "AT_RISK", "threshold_elapsed",
                null, 5, Instant.EPOCH);
    }
}

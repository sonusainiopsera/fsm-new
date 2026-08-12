package com.fieldservice.sla.internal;

import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.sla.SlaBreachReasonCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlaBreachService} overrun arithmetic and idempotency.
 */
@ExtendWith(MockitoExtension.class)
class SlaBreachServiceTest {

    @Mock private SlaBreachRepository repository;
    @Mock private SlaRiskFlagRepository flagRepository;
    @Mock private DomainEventPublisher eventPublisher;

    private SlaBreachService service;

    @BeforeEach
    void setUp() {
        service = new SlaBreachService(repository, flagRepository, eventPublisher,
                new SimpleMeterRegistry());
    }

    // ── Detect idempotency ─────────────────────────────────────────────────────

    @Test
    void detect_whenBreachAlreadyExists_skipsInsert() {
        UUID workOrderId = UUID.randomUUID();
        when(repository.existsByWorkOrderIdAndBreachType(workOrderId, "RESOLUTION")).thenReturn(true);

        service.detect(workOrderId, "RESOLUTION",
                Instant.parse("2026-08-17T09:00:00Z"),
                Instant.parse("2026-08-17T10:00:00Z"),
                60, 0, List.of());

        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void detect_negativeOverrun_isClamped() {
        UUID workOrderId = UUID.randomUUID();
        when(repository.existsByWorkOrderIdAndBreachType(workOrderId, "RESOLUTION")).thenReturn(false);
        SlaBreachEntity saved = SlaBreachEntity.detect(workOrderId, "RESOLUTION",
                Instant.parse("2026-08-17T09:00:00Z"),
                Instant.parse("2026-08-17T08:50:00Z"), // 10 min before — negative overrun
                0, 0);
        when(repository.saveAndFlush(any())).thenReturn(saved);

        service.detect(workOrderId, "RESOLUTION",
                Instant.parse("2026-08-17T09:00:00Z"),
                Instant.parse("2026-08-17T08:50:00Z"),
                -5, 0, List.of());

        // Verify saveAndFlush was called (clamped to 0, no exception)
        verify(repository, times(1)).saveAndFlush(any());
    }

    // ── Finalise ───────────────────────────────────────────────────────────────

    @Test
    void finalise_writesCorrectFinalOverrun() {
        UUID workOrderId = UUID.randomUUID();
        Instant effectiveDeadline = Instant.parse("2026-08-17T09:00:00Z");
        Instant closureInstant    = Instant.parse("2026-08-17T10:30:00Z"); // 90m overrun

        SlaBreachEntity breach = SlaBreachEntity.detect(workOrderId, "RESOLUTION",
                effectiveDeadline, effectiveDeadline, 60, 0);

        when(repository.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                .thenReturn(List.of(breach));
        when(repository.save(any())).thenReturn(breach);

        service.finalise(workOrderId, closureInstant);

        assertThat(breach.getFinalOverrunMinutes()).isEqualTo(90);
        verify(repository, times(1)).save(breach);
    }

    @Test
    void finalise_whenNoUnfinalisedBreaches_doesNothing() {
        UUID workOrderId = UUID.randomUUID();
        when(repository.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                .thenReturn(List.of());

        service.finalise(workOrderId, Instant.parse("2026-08-17T10:00:00Z"));

        verify(repository, never()).save(any());
    }

    @Test
    void finalise_idempotent_doesNotOverwriteAlreadyFinalisedValue() {
        UUID workOrderId = UUID.randomUUID();
        // Both calls return empty (second finalisation finds no unfinalisedrecords)
        when(repository.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                .thenReturn(List.of());

        service.finalise(workOrderId, Instant.parse("2026-08-17T10:00:00Z"));
        service.finalise(workOrderId, Instant.parse("2026-08-17T11:00:00Z")); // replay

        verify(repository, never()).save(any());
    }

    // ── writeFinalOverrun idempotency on entity ────────────────────────────────

    @Test
    void entityWriteFinalOverrun_idempotent_doesNotOverwrite() {
        SlaBreachEntity breach = SlaBreachEntity.detect(UUID.randomUUID(), "RESOLUTION",
                Instant.parse("2026-08-17T09:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"), 0, 0);

        breach.writeFinalOverrun(60);
        breach.writeFinalOverrun(999); // second call with different value

        assertThat(breach.getFinalOverrunMinutes()).isEqualTo(60); // first value preserved
    }

    // ── Overrun arithmetic with pauses ─────────────────────────────────────────

    @Test
    void finalise_pausedMinutesNotDoubleCountedInFinalOverrun() {
        // Effective deadline already accounts for pause time.
        // Raw deadline = 09:00, pause = 30 min, effectiveDeadline = 09:30.
        // Closure at 10:00 → final overrun = 30 min (not 60).
        UUID workOrderId = UUID.randomUUID();
        Instant effectiveDeadline = Instant.parse("2026-08-17T09:30:00Z");
        Instant closureInstant    = Instant.parse("2026-08-17T10:00:00Z");

        SlaBreachEntity breach = SlaBreachEntity.detect(workOrderId, "RESOLUTION",
                effectiveDeadline, effectiveDeadline, 0, 30);

        when(repository.findByWorkOrderIdAndFinalOverrunMinutesIsNull(workOrderId))
                .thenReturn(List.of(breach));
        when(repository.save(any())).thenReturn(breach);

        service.finalise(workOrderId, closureInstant);

        assertThat(breach.getFinalOverrunMinutes()).isEqualTo(30);
    }

    // ── Attribution ────────────────────────────────────────────────────────────

    @Test
    void attributeReason_setsReasonCodeOnEntity() {
        UUID breachId = UUID.randomUUID();
        UUID actor    = UUID.randomUUID();
        Instant now   = Instant.parse("2026-08-17T10:00:00Z");

        SlaBreachEntity breach = SlaBreachEntity.detect(breachId, "RESOLUTION",
                Instant.parse("2026-08-17T09:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"), 0, 0);

        when(repository.findById(breachId)).thenReturn(Optional.of(breach));
        when(repository.save(any())).thenReturn(breach);

        service.attributeReason(breachId, SlaBreachReasonCode.PARTS_UNAVAILABLE,
                "Stock shortage", actor, now);

        assertThat(breach.getReasonCode()).isEqualTo("PARTS_UNAVAILABLE");
    }
}

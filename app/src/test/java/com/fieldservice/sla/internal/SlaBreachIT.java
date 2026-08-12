package com.fieldservice.sla.internal;

import com.fieldservice.support.PostgresContainerSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration tests for SLA breach recording (WO-144).
 *
 * <p>Uses fixture work orders from V132. Tests cover:
 * <ul>
 *   <li>Single-record idempotency per (work_order, breach_type)</li>
 *   <li>Finalisation writes final_overrun_minutes and is idempotent</li>
 *   <li>Fixture scenarios verify all five breach states</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Import(SlaBreachIT.TestConfig.class)
class SlaBreachIT extends PostgresContainerSupport {

    private static final UUID WO_RESOLUTION = UUID.fromString("aa100000-0000-0000-0000-000000000002");
    private static final UUID WO_UNATTRIBUTED = UUID.fromString("aa100000-0000-0000-0000-000000000005");
    private static final UUID BREACH_UNATTRIBUTED = UUID.fromString("bb100000-0000-0000-0000-000000000005");
    private static final UUID WO_CLOSED   = UUID.fromString("aa100000-0000-0000-0000-000000000004");

    @Autowired private SlaBreachService breachService;
    @Autowired private SlaBreachRepository breachRepository;
    @Autowired private SlaRiskFlagRepository flagRepository;

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Test
    void detect_calledTwice_onlyOneRowCreated() {
        UUID workOrderId = UUID.randomUUID();
        // Insert a minimal work_order row so FK constraint is satisfied
        insertMinimalWorkOrder(workOrderId);

        Instant deadline   = Instant.parse("2026-08-17T09:00:00Z");
        Instant detectedAt = Instant.parse("2026-08-17T10:00:00Z");

        breachService.detect(workOrderId, "RESOLUTION", deadline, detectedAt, 60, 0, List.of());
        breachService.detect(workOrderId, "RESOLUTION", deadline, detectedAt, 60, 0, List.of());

        long count = breachRepository.findFiltered(null, null, null)
                .stream().filter(b -> b.getWorkOrderId().equals(workOrderId)).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void detect_differentBreachTypes_createsTwo() {
        UUID workOrderId = UUID.randomUUID();
        insertMinimalWorkOrder(workOrderId);

        Instant deadline   = Instant.parse("2026-08-17T09:00:00Z");
        Instant detectedAt = Instant.parse("2026-08-17T10:00:00Z");

        breachService.detect(workOrderId, "RESOLUTION", deadline, detectedAt, 60, 0, List.of());
        breachService.detect(workOrderId, "RESPONSE",   deadline, detectedAt, 90, 0, List.of());

        long count = breachRepository.findFiltered(null, null, null)
                .stream().filter(b -> b.getWorkOrderId().equals(workOrderId)).count();
        assertThat(count).isEqualTo(2);
    }

    // ── Finalisation ───────────────────────────────────────────────────────────

    @Test
    void finalise_writesCorrectFinalOverrun() {
        // Scenario 5 fixture: unattributed breach with final_overrun_minutes = null
        Optional<SlaBreachEntity> breach =
                breachRepository.findByWorkOrderIdAndBreachType(WO_UNATTRIBUTED, "RESOLUTION");
        assertThat(breach).isPresent();
        assertThat(breach.get().getFinalOverrunMinutes()).isNull();

        // Closure at 13:30 → effective deadline 13:00 → 30 min overrun
        Instant closureInstant = Instant.parse("2026-08-14T13:30:00Z");
        breachService.finalise(WO_UNATTRIBUTED, closureInstant);

        Optional<SlaBreachEntity> after =
                breachRepository.findByWorkOrderIdAndBreachType(WO_UNATTRIBUTED, "RESOLUTION");
        assertThat(after.get().getFinalOverrunMinutes()).isEqualTo(30);
    }

    @Test
    void finalise_idempotent_doesNotOverwriteExistingFinalOverrun() {
        // Scenario 4 fixture: already has final_overrun_minutes = 45
        Optional<SlaBreachEntity> breach =
                breachRepository.findByWorkOrderIdAndBreachType(WO_CLOSED, "RESOLUTION");
        assertThat(breach).isPresent();
        assertThat(breach.get().getFinalOverrunMinutes()).isEqualTo(45);

        // Replay finalisation with a different closure instant
        breachService.finalise(WO_CLOSED, Instant.parse("2026-08-13T15:00:00Z"));

        Optional<SlaBreachEntity> after =
                breachRepository.findByWorkOrderIdAndBreachType(WO_CLOSED, "RESOLUTION");
        assertThat(after.get().getFinalOverrunMinutes()).isEqualTo(45); // unchanged
    }

    // ── Fixture scenarios ─────────────────────────────────────────────────────

    @Test
    void fixture_responseOnlyBreachHasNoResolutionBreach() {
        UUID woResponseOnly = UUID.fromString("aa100000-0000-0000-0000-000000000001");
        assertThat(breachRepository.existsByWorkOrderIdAndBreachType(woResponseOnly, "RESPONSE")).isTrue();
        assertThat(breachRepository.existsByWorkOrderIdAndBreachType(woResponseOnly, "RESOLUTION")).isFalse();
    }

    @Test
    void fixture_pauseSavedWorkOrderHasNoBreach() {
        UUID woPauseSaved = UUID.fromString("aa100000-0000-0000-0000-000000000003");
        assertThat(breachRepository.findByWorkOrderIdAndBreachType(woPauseSaved, "RESOLUTION")).isEmpty();
        assertThat(breachRepository.findByWorkOrderIdAndBreachType(woPauseSaved, "RESPONSE")).isEmpty();
    }

    @Test
    void fixture_unattributedBreachHasNullReasonCode() {
        Optional<SlaBreachEntity> breach =
                breachRepository.findByWorkOrderIdAndBreachType(WO_UNATTRIBUTED, "RESOLUTION");
        assertThat(breach).isPresent();
        assertThat(breach.get().getReasonCode()).isNull();
    }

    // ── Query (SlaBreachAdminService) ─────────────────────────────────────────

    @Test
    void listBreaches_filterUnattributed_returnsOnlyUnattributed() {
        var results = breachService.listBreaches(null, Boolean.TRUE, null, null,
                "detectedAt", true, 0, 50);
        // Only scenario 5 (unattributed) + scenario 2 actually has reason_code; scenario 5 doesn't
        assertThat(results).allMatch(b -> b.reasonCode() == null);
    }

    @Test
    void listBreaches_filterByType_returnsOnlyMatchingType() {
        var resolutionBreaches = breachService.listBreaches("RESOLUTION", null, null, null,
                "detectedAt", true, 0, 50);
        assertThat(resolutionBreaches).allMatch(b -> "RESOLUTION".equals(b.breachType()));
    }

    @Test
    void listBreaches_pageSizeEnforced() {
        var page0 = breachService.listBreaches(null, null, null, null,
                "detectedAt", true, 0, 1);
        assertThat(page0).hasSize(1);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    @Autowired
    private jakarta.persistence.EntityManager em;

    private void insertMinimalWorkOrder(UUID workOrderId) {
        // Insert customer for FK
        UUID customerId = UUID.fromString("ff100000-0000-0000-0000-000000000001");
        em.createNativeQuery("""
                INSERT INTO work_order (id, customer_id, title, description, state, priority,
                                        created_at, updated_at, version)
                VALUES (?, ?, 'IT Test WO', 'Test', 'OPEN', 'MEDIUM', now(), now(), 0)
                ON CONFLICT DO NOTHING
                """)
          .setParameter(1, workOrderId)
          .setParameter(2, customerId)
          .executeUpdate();
    }

    // ── Test config ───────────────────────────────────────────────────────────

    @Configuration
    static class TestConfig {
        @Bean
        @Primary
        MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}

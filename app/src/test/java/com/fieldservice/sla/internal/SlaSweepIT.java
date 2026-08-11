package com.fieldservice.sla.internal;

import com.fieldservice.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SlaEvaluationScheduler} sweep behaviour.
 *
 * <p>Uses a fixed clock set to 2026-01-01T12:00:00Z to match the V123 fixture timestamps.
 * The distributed lock is disabled so a single test context can run the sweep without
 * contending with itself. The worker profile is activated so the scheduler bean is present.
 *
 * <p>Tests are non-transactional because the scheduler uses {@code REQUIRES_NEW} transactions
 * internally — assertions are made over raw JDBC after the sweep completes.
 */
@ActiveProfiles({"test", "worker"})
@TestPropertySource(properties = {
        "app.sla.sweep.lock-enabled=false",
        "app.sla.sweep.tick-interval-ms=60000"
})
@Import(SlaSweepIT.FixedClockConfig.class)
public class SlaSweepIT extends AbstractIntegrationTest {

    /** Fixed clock for test determinism — matches fixture timestamps in V123. */
    static final Instant TEST_NOW = Instant.parse("2026-01-01T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock slaClock() {
            return Clock.fixed(TEST_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private SlaEvaluationScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanSlaFlags() {
        jdbcTemplate.execute("DELETE FROM sla_risk_flag WHERE work_order_id LIKE '7a000000%'");
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AT_RISK flag raised for work order past at-risk threshold (AC-4, BR-13)")
    void sweep_raisesAtRiskFlag_forAtRiskWorkOrder() {
        scheduler.doSweep();

        List<Map<String, Object>> flags = jdbcTemplate.queryForList(
                "SELECT * FROM sla_risk_flag WHERE work_order_id = ? AND cleared_at IS NULL",
                UUID.fromString("7a000000-0000-0000-0000-000000000002"));

        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).get("flag_type")).isEqualTo("AT_RISK");
        assertThat(flags.get(0).get("trigger_reason")).isEqualTo("at_risk_threshold_elapsed");
        assertThat(flags.get(0).get("created_by_system")).isEqualTo(true);
    }

    @Test
    @DisplayName("PROJECTED_OVERRUN flag raised for work order past resolution deadline (AC-4)")
    void sweep_raisesProjectedOverrunFlag_forOverrunWorkOrder() {
        scheduler.doSweep();

        List<Map<String, Object>> flags = jdbcTemplate.queryForList(
                "SELECT * FROM sla_risk_flag WHERE work_order_id = ? AND cleared_at IS NULL",
                UUID.fromString("7a000000-0000-0000-0000-000000000003"));

        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).get("flag_type")).isEqualTo("PROJECTED_OVERRUN");
        assertThat(flags.get(0).get("trigger_reason")).isEqualTo("resolution_deadline_passed");
    }

    @Test
    @DisplayName("No flag raised for healthy work order (AC-4)")
    void sweep_noFlag_forHealthyWorkOrder() {
        scheduler.doSweep();

        List<Map<String, Object>> flags = jdbcTemplate.queryForList(
                "SELECT * FROM sla_risk_flag WHERE work_order_id = ? AND cleared_at IS NULL",
                UUID.fromString("7a000000-0000-0000-0000-000000000001"));

        assertThat(flags).isEmpty();
    }

    @Test
    @DisplayName("Pre-existing open flag cleared for terminal work order (AC-5)")
    void sweep_clearsFlag_forTerminalWorkOrder() {
        scheduler.doSweep();

        List<Map<String, Object>> flags = jdbcTemplate.queryForList(
                "SELECT * FROM sla_risk_flag WHERE work_order_id = ?",
                UUID.fromString("7a000000-0000-0000-0000-000000000004"));

        assertThat(flags).hasSize(1); // fixture row still present
        assertThat(flags.get(0).get("cleared_at")).isNotNull();
        assertThat(flags.get(0).get("clear_reason")).isEqualTo("terminal_state");
    }

    // ── Idempotency (AC-6) ────────────────────────────────────────────────────

    @Test
    @DisplayName("Sweep is idempotent — running twice does not create duplicate flags (AC-6)")
    void sweep_idempotent_noDuplicateFlags() {
        scheduler.doSweep();
        scheduler.doSweep(); // second sweep must not create duplicates

        List<Map<String, Object>> flags = jdbcTemplate.queryForList(
                "SELECT * FROM sla_risk_flag WHERE work_order_id = ? AND cleared_at IS NULL",
                UUID.fromString("7a000000-0000-0000-0000-000000000002"));

        assertThat(flags).hasSize(1); // still exactly one open AT_RISK flag
    }

    // ── Outbox events (AC-8) ──────────────────────────────────────────────────

    @Test
    @DisplayName("SlaRiskFlagged outbox event published atomically with flag row (AC-8)")
    void sweep_publishesOutboxEvent_whenFlagRaised() {
        scheduler.doSweep();

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT * FROM outbox_event WHERE event_type = 'SlaRiskFlagged' " +
                "AND aggregate_id = ?",
                UUID.fromString("7a000000-0000-0000-0000-000000000002"));

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).get("aggregate_type")).isEqualTo("WorkOrder");
    }

    @Test
    @DisplayName("SlaRiskCleared outbox event published when terminal flag is cleared (AC-8)")
    void sweep_publishesClearEvent_whenFlagCleared() {
        scheduler.doSweep();

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT * FROM outbox_event WHERE event_type = 'SlaRiskCleared' " +
                "AND aggregate_id = ?",
                UUID.fromString("7a000000-0000-0000-0000-000000000004"));

        assertThat(events).isNotEmpty();
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Micrometer sweep timer is recorded after each sweep pass (AC-7)")
    void sweep_recordsDurationTimer() {
        scheduler.doSweep();

        double count = meterRegistry.find("sla_sweep_duration_seconds").timer() != null
                ? meterRegistry.find("sla_sweep_duration_seconds").timer().count()
                : -1;
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    // ── Profile guard (AC-1) ─────────────────────────────────────────────────

    @Test
    @DisplayName("Scheduler bean is present under worker profile (AC-1)")
    void schedulerBean_presentUnderWorkerProfile() {
        assertThat(scheduler).isNotNull();
    }
}

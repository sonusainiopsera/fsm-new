package com.fieldservice.sla.internal;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link SlaRiskEvaluator}.
 *
 * <p>No Spring context, no I/O. All tests use fixed clocks via explicit {@code Instant now} arguments.
 *
 * <h3>BR-13 threshold</h3>
 * Resolution window = 240 min; at-risk fraction = 0.80; at-risk threshold = 192 min elapsed.
 * That means atRiskAt = createdAt + 192 min, resolutionDueAt = createdAt + 240 min.
 */
class SlaRiskEvaluatorTest {

    private static final UUID WO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SlaRiskEvaluator evaluator;

    // BR-13 fixture
    private static final Instant CREATED_AT       = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT_RISK_AT        = CREATED_AT.plusSeconds(192 * 60); // +192 min
    private static final Instant RESOLUTION_DUE_AT = CREATED_AT.plusSeconds(240 * 60); // +240 min

    @BeforeEach
    void setUp() {
        evaluator = new SlaRiskEvaluator();
    }

    // ── Healthy path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Healthy when deadline not reached and no existing flag")
    void healthy_beforeAtRiskThreshold() {
        Instant now = AT_RISK_AT.minusSeconds(60); // 1 minute before at-risk
        WorkOrderRiskSnapshot snapshot = snapshotNoFlag(WorkOrderState.IN_PROGRESS, now, AT_RISK_AT, RESOLUTION_DUE_AT);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Healthy.class);
        RiskDecision.Healthy h = (RiskDecision.Healthy) decision;
        assertThat(h.clearReason()).isNull(); // no existing flag to clear
    }

    @Test
    @DisplayName("Healthy with clearReason when existing flag and work order recovered (BR-13)")
    void healthy_clearsExistingFlag_whenReturnedHealthy() {
        Instant now = AT_RISK_AT.minusSeconds(60); // before threshold
        WorkOrderRiskSnapshot snapshot = snapshot(WorkOrderState.IN_PROGRESS, "AT_RISK", AT_RISK_AT, RESOLUTION_DUE_AT, false);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Healthy.class);
        RiskDecision.Healthy h = (RiskDecision.Healthy) decision;
        assertThat(h.clearReason()).isEqualTo("returned_healthy");
    }

    // ── Terminal states ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Healthy(terminal_state) when work order is COMPLETED and has open flag")
    void healthy_terminalState_clearsFlag() {
        Instant now = RESOLUTION_DUE_AT.plusSeconds(60);
        WorkOrderRiskSnapshot snapshot = snapshot(WorkOrderState.COMPLETED, "AT_RISK", AT_RISK_AT, RESOLUTION_DUE_AT, false);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Healthy.class);
        assertThat(((RiskDecision.Healthy) decision).clearReason()).isEqualTo("terminal_state");
    }

    @Test
    @DisplayName("Healthy(null) when work order is CANCELLED and has no open flag")
    void healthy_terminalState_noFlag() {
        Instant now = RESOLUTION_DUE_AT.plusSeconds(60);
        WorkOrderRiskSnapshot snapshot = snapshotNoFlag(WorkOrderState.CANCELLED, now, AT_RISK_AT, RESOLUTION_DUE_AT);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Healthy.class);
        assertThat(((RiskDecision.Healthy) decision).clearReason()).isNull();
    }

    // ── Paused clock ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Paused when SLA clock is currently paused")
    void paused_whenCurrentlyPaused() {
        Instant now = RESOLUTION_DUE_AT.plusSeconds(60); // even past the deadline
        WorkOrderRiskSnapshot snapshot = new WorkOrderRiskSnapshot(
                WO_ID, WorkOrderState.ON_HOLD, "HIGH", CREATED_AT,
                null, AT_RISK_AT, RESOLUTION_DUE_AT,
                true,  // currentlyPaused
                null);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Paused.class);
    }

    // ── BR-13 AT_RISK threshold ───────────────────────────────────────────────

    @Test
    @DisplayName("AtRisk exactly at the at-risk threshold (BR-13)")
    void atRisk_exactlyAtThreshold() {
        Instant now = AT_RISK_AT; // exactly at threshold
        WorkOrderRiskSnapshot snapshot = snapshotNoFlag(WorkOrderState.IN_PROGRESS, now, AT_RISK_AT, RESOLUTION_DUE_AT);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.AtRisk.class);
        RiskDecision.AtRisk ar = (RiskDecision.AtRisk) decision;
        assertThat(ar.triggerReason()).isEqualTo("at_risk_threshold_elapsed");
        assertThat(ar.minutesRemaining()).isEqualTo(48); // 240 - 192 = 48 min
    }

    @Test
    @DisplayName("AtRisk with correct minutesRemaining calculation")
    void atRisk_minutesRemainingCorrect() {
        Instant now = AT_RISK_AT.plusSeconds(10 * 60); // 10 min after at-risk threshold
        WorkOrderRiskSnapshot snapshot = snapshotNoFlag(WorkOrderState.IN_PROGRESS, now, AT_RISK_AT, RESOLUTION_DUE_AT);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.AtRisk.class);
        assertThat(((RiskDecision.AtRisk) decision).minutesRemaining()).isEqualTo(38); // 48 - 10
    }

    // ── Idempotency — AT_RISK already open ────────────────────────────────────

    @Test
    @DisplayName("Healthy(null) when AT_RISK already open — idempotent, no duplicate flag (AC-6)")
    void atRisk_idempotent_noActionWhenAlreadyOpen() {
        Instant now = AT_RISK_AT.plusSeconds(60);
        // existingOpenFlagType = AT_RISK — flag already open
        WorkOrderRiskSnapshot snapshot = snapshot(WorkOrderState.IN_PROGRESS, "AT_RISK", AT_RISK_AT, RESOLUTION_DUE_AT, false);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        // Evaluator should signal "no action needed" when flag already open for this level
        assertThat(decision).isInstanceOf(RiskDecision.Healthy.class);
        assertThat(((RiskDecision.Healthy) decision).clearReason()).isNull();
    }

    // ── Projected overrun ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ProjectedOverrun when now >= effectiveResolutionDueAt")
    void projectedOverrun_pastDeadline() {
        Instant now = RESOLUTION_DUE_AT.plusSeconds(5 * 60); // 5 min past deadline
        WorkOrderRiskSnapshot snapshot = snapshotNoFlag(WorkOrderState.IN_PROGRESS, now, AT_RISK_AT, RESOLUTION_DUE_AT);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.ProjectedOverrun.class);
        RiskDecision.ProjectedOverrun po = (RiskDecision.ProjectedOverrun) decision;
        assertThat(po.triggerReason()).isEqualTo("resolution_deadline_passed");
        assertThat(po.minutesRemaining()).isEqualTo(-5); // negative = minutes PAST deadline
    }

    @Test
    @DisplayName("ProjectedOverrun exactly at deadline")
    void projectedOverrun_exactlyAtDeadline() {
        Instant now = RESOLUTION_DUE_AT;
        WorkOrderRiskSnapshot snapshot = snapshotNoFlag(WorkOrderState.IN_PROGRESS, now, AT_RISK_AT, RESOLUTION_DUE_AT);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.ProjectedOverrun.class);
    }

    // ── Null deadline ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Healthy when effectiveResolutionDueAt is null — no SLA policy yet")
    void healthy_nullDeadline() {
        Instant now = Instant.now();
        WorkOrderRiskSnapshot snapshot = new WorkOrderRiskSnapshot(
                WO_ID, WorkOrderState.NEW, "STANDARD", CREATED_AT,
                null, null, null, // no deadlines
                false, null);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Healthy.class);
        assertThat(((RiskDecision.Healthy) decision).clearReason()).isNull();
    }

    // ── Pause extends effective deadline ─────────────────────────────────────

    @Test
    @DisplayName("AtRisk not raised while pause is active even if raw deadline has passed")
    void paused_notAtRisk_whenRawDeadlinePassedButClockPaused() {
        // Raw deadline is past but pause is active — evaluator should return Paused
        Instant now = RESOLUTION_DUE_AT.plusSeconds(60);
        WorkOrderRiskSnapshot snapshot = new WorkOrderRiskSnapshot(
                WO_ID, WorkOrderState.ON_HOLD, "HIGH", CREATED_AT,
                null, AT_RISK_AT.plusSeconds(600), RESOLUTION_DUE_AT.plusSeconds(600), // effective = extended
                true, // currentlyPaused
                null);

        RiskDecision decision = evaluator.evaluate(snapshot, now);

        assertThat(decision).isInstanceOf(RiskDecision.Paused.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkOrderRiskSnapshot snapshotNoFlag(WorkOrderState state, Instant now,
                                                  Instant atRiskAt, Instant resolutionDueAt) {
        return new WorkOrderRiskSnapshot(
                WO_ID, state, "HIGH", CREATED_AT,
                null, atRiskAt, resolutionDueAt,
                false, null);
    }

    private WorkOrderRiskSnapshot snapshot(WorkOrderState state, String existingFlagType,
                                            Instant atRiskAt, Instant resolutionDueAt,
                                            boolean paused) {
        return new WorkOrderRiskSnapshot(
                WO_ID, state, "HIGH", CREATED_AT,
                null, atRiskAt, resolutionDueAt,
                paused, existingFlagType);
    }
}

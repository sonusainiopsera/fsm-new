package com.fieldservice.dispatch.web;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.ExcludedCandidate;
import com.fieldservice.dispatch.api.ExclusionReason;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@link ReassignmentController} (WO-139).
 *
 * <p>Covers:
 * <ul>
 *   <li>200 happy path from ASSIGNED state</li>
 *   <li>200 reassignment from EN_ROUTE state</li>
 *   <li>200 reassignment from IN_PROGRESS state</li>
 *   <li>200 reassignment from ON_HOLD state</li>
 *   <li>200 with appointment acknowledgement (confirmed window)</li>
 *   <li>400 missing reassignmentReason</li>
 *   <li>400 invalid reassignmentReason enum value</li>
 *   <li>400 same-technician no-op</li>
 *   <li>403 TECHNICIAN role denied</li>
 *   <li>409 illegal state (NEW, COMPLETED, CANCELLED)</li>
 *   <li>422 appointment breach without acknowledgement</li>
 *   <li>422 certification guard refusal</li>
 *   <li>History reconstruction: two successive reassignments produce correct supersede chain</li>
 * </ul>
 */
@Tag("integration")
@Import(ReassignmentControllerIT.StubEligibility.class)
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/seed-wo139.sql",
        "classpath:fixtures/seed-appointments.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReassignmentControllerIT extends AbstractIntegrationTest {

    // ── Work order IDs (from seed-wo139.sql) ──────────────────────────────────
    static final UUID WO_ASSIGNED_ID     = UUID.fromString("00000000-0000-7139-8000-000000000001");
    static final UUID WO_EN_ROUTE_ID     = UUID.fromString("00000000-0000-7139-8000-000000000002");
    static final UUID WO_IN_PROGRESS_ID  = UUID.fromString("00000000-0000-7139-8000-000000000003");
    static final UUID WO_ON_HOLD_ID      = UUID.fromString("00000000-0000-7139-8000-000000000004");
    static final UUID WO_NEW_ID          = UUID.fromString("00000000-0000-7139-8000-000000000005");
    static final UUID WO_COMPLETED_ID    = UUID.fromString("00000000-0000-7139-8000-000000000006");
    static final UUID WO_CANCELLED_ID    = UUID.fromString("00000000-0000-7139-8000-000000000007");
    static final UUID WO_UNKNOWN_ID      = UUID.fromString("00000000-0000-7139-8000-999999999999");

    // ── Appointment test IDs (from seed-appointments.sql) ─────────────────────
    static final UUID WO_APPT_CONFIRMED  = UUID.fromString("00000000-0000-7139-9000-000000000001");
    static final UUID WO_APPT_UNCONFIRMED= UUID.fromString("00000000-0000-7139-9000-000000000002");
    static final UUID WO_APPT_PAST       = UUID.fromString("00000000-0000-7139-9000-000000000003");

    // ── Technician IDs ────────────────────────────────────────────────────────
    static final UUID TECH_CURRENT    = UUID.fromString("00000000-0000-7139-8000-000000000020");
    static final UUID TECH_ELIGIBLE   = UUID.fromString("00000000-0000-7139-8000-000000000010");
    static final UUID TECH_INELIGIBLE = UUID.fromString("00000000-0000-7139-8000-000000000011");

    @Autowired DatabaseCleaner dbCleaner;
    @Autowired JdbcTemplate    jdbc;

    @AfterEach
    void clean() { dbCleaner.truncateAll(); }

    // ── 200 happy path: ASSIGNED → reassign to eligible technician ────────────

    @Test
    @DisplayName("200 — reassign ASSIGNED work order creates supersede chain and new assignment")
    void reassign_fromAssigned_returns200AndSupersedes() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignmentId", notNullValue()))
                .andExpect(jsonPath("$.data.supersededAssignmentId", notNullValue()))
                .andExpect(jsonPath("$.data.workOrderId", is(WO_ASSIGNED_ID.toString())))
                .andExpect(jsonPath("$.data.technicianId", is(TECH_ELIGIBLE.toString())))
                .andExpect(jsonPath("$.data.state", is("ASSIGNED")))
                .andExpect(jsonPath("$.data.reassignmentReason", is("SLA_RISK")))
                .andExpect(jsonPath("$.data.appointmentImpactRecorded", is(false)));

        // Work order assigned_technician_id updated; state unchanged
        String techId = jdbc.queryForObject(
                "SELECT assigned_technician_id::text FROM work_order WHERE id = ?",
                String.class, WO_ASSIGNED_ID);
        assertThat(techId).isEqualTo(TECH_ELIGIBLE.toString());

        String state = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, WO_ASSIGNED_ID);
        assertThat(state).isEqualTo("ASSIGNED");

        // Old assignment is end-dated
        Integer endDatedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND end_at IS NOT NULL",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(endDatedCount).isEqualTo(1);

        // New active assignment exists
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND end_at IS NULL",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(activeCount).isEqualTo(1);

        // Two outbox events published (revoke + assign)
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(outboxCount).isGreaterThanOrEqualTo(2);
    }

    // ── 200 from EN_ROUTE ─────────────────────────────────────────────────────

    @Test
    @DisplayName("200 — reassign EN_ROUTE work order succeeds; state remains EN_ROUTE")
    void reassign_fromEnRoute_returns200_stateUnchanged() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_EN_ROUTE_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"TECHNICIAN_UNAVAILABLE"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("EN_ROUTE")));
    }

    // ── 200 from IN_PROGRESS ──────────────────────────────────────────────────

    @Test
    @DisplayName("200 — reassign IN_PROGRESS work order succeeds; state remains IN_PROGRESS")
    void reassign_fromInProgress_returns200_stateUnchanged() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_IN_PROGRESS_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"JOB_OVERRUN"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("IN_PROGRESS")));
    }

    // ── 200 from ON_HOLD ──────────────────────────────────────────────────────

    @Test
    @DisplayName("200 — reassign ON_HOLD work order succeeds; state remains ON_HOLD")
    void reassign_fromOnHold_returns200_stateUnchanged() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ON_HOLD_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SKILL_MISMATCH"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("ON_HOLD")));
    }

    // ── 200 with appointment acknowledgement ──────────────────────────────────

    @Test
    @DisplayName("200 — reassign with confirmed future appointment and acknowledgement succeeds")
    void reassign_confirmedAppointment_withAck_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_APPT_CONFIRMED)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"TECHNICIAN_UNAVAILABLE",
                                "appointmentImpactAcknowledgement":"Customer notified and rescheduled"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentImpactRecorded", is(true)));

        // Appointment impact reason persisted on the new assignment
        String impactReason = jdbc.queryForObject(
                "SELECT appointment_impact_reason FROM assignment WHERE work_order_id = ? AND end_at IS NULL",
                String.class, WO_APPT_CONFIRMED);
        assertThat(impactReason).isEqualTo("Customer notified and rescheduled");
    }

    // ── 200 unconfirmed appointment — guard does not fire ─────────────────────

    @Test
    @DisplayName("200 — unconfirmed appointment does not require acknowledgement")
    void reassign_unconfirmedAppointment_noAckRequired_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_APPT_UNCONFIRMED)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentImpactRecorded", is(false)));
    }

    // ── 200 past confirmed appointment — guard does not fire ──────────────────

    @Test
    @DisplayName("200 — past confirmed appointment does not require acknowledgement")
    void reassign_pastConfirmedAppointment_noAckRequired_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_APPT_PAST)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"CUSTOMER_REQUEST"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk());
    }

    // ── 400 missing reassignmentReason ────────────────────────────────────────

    @Test
    @DisplayName("400 — missing reassignmentReason returns 400 with field error")
    void reassign_missingReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field", is("reassignmentReason")));
    }

    // ── 400 invalid reassignmentReason ────────────────────────────────────────

    @Test
    @DisplayName("400 — unknown reassignmentReason enum value returns 400")
    void reassign_invalidReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"NOT_A_REAL_REASON"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isBadRequest());
    }

    // ── 400 same technician ───────────────────────────────────────────────────

    @Test
    @DisplayName("400 — reassigning to the same technician returns 400 no-op")
    void reassign_sameTechnician_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_CURRENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field", is("technicianId")));
    }

    // ── 403 role denied ───────────────────────────────────────────────────────

    @Test
    @DisplayName("403 — TECHNICIAN role cannot reassign work orders")
    void reassign_technicianRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForTechnician(UUID.randomUUID(), TECH_ELIGIBLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isForbidden());
    }

    // ── 403 — non-existent work order (non-disclosure) ────────────────────────

    @Test
    @DisplayName("403 — non-existent work order returns 403 (non-disclosure)")
    void reassign_unknownWorkOrder_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_UNKNOWN_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isForbidden());
    }

    // ── 409 illegal state ─────────────────────────────────────────────────────

    @Test
    @DisplayName("409 — NEW work order returns 409 with current state named")
    void reassign_newState_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("WORK_ORDER_ILLEGAL_TRANSITION")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("currentState")))
                .andExpect(jsonPath("$.fieldErrors[0].code", is("NEW")));
    }

    @Test
    @DisplayName("409 — COMPLETED work order returns 409")
    void reassign_completedState_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_COMPLETED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("WORK_ORDER_ILLEGAL_TRANSITION")));
    }

    @Test
    @DisplayName("409 — CANCELLED work order returns 409")
    void reassign_cancelledState_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_CANCELLED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("WORK_ORDER_ILLEGAL_TRANSITION")));
    }

    // ── 422 appointment breach without acknowledgement ────────────────────────

    @Test
    @DisplayName("422 — confirmed future appointment without acknowledgement returns 422")
    void reassign_confirmedAppointment_noAck_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_APPT_CONFIRMED)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"TECHNICIAN_UNAVAILABLE"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("CERTIFICATION_NOT_CURRENT")));

        // Work order must still be assigned to TECH_CURRENT — no change
        String techId = jdbc.queryForObject(
                "SELECT assigned_technician_id::text FROM work_order WHERE id = ?",
                String.class, WO_APPT_CONFIRMED);
        assertThat(techId).isEqualTo(TECH_CURRENT.toString());
    }

    // ── 422 certification guard ───────────────────────────────────────────────

    @Test
    @DisplayName("422 — ineligible technician returns 422 regardless of override reason")
    void reassign_ineligibleTechnician_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK","overrideReason":"I insist"}
                                """.formatted(TECH_INELIGIBLE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("CERTIFICATION_NOT_CURRENT")));

        // Work order technician must be unchanged
        String techId = jdbc.queryForObject(
                "SELECT assigned_technician_id::text FROM work_order WHERE id = ?",
                String.class, WO_ASSIGNED_ID);
        assertThat(techId).isEqualTo(TECH_CURRENT.toString());
    }

    // ── History reconstruction: two successive reassignments ─────────────────

    @Test
    @DisplayName("Two successive reassignments produce correct supersede chain with one active assignment")
    void reassign_twoSuccessive_correctSupersedeCh_ain() throws Exception {
        // First reassignment: TECH_CURRENT → TECH_ELIGIBLE
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"SLA_RISK"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk());

        // Need a third technician for the second reassignment
        UUID techThird = UUID.fromString("00000000-0000-7139-8000-000000000030");

        // Second reassignment: TECH_ELIGIBLE → techThird
        // Use a new eligible technician — stub accepts any ID not equal to TECH_INELIGIBLE
        mockMvc.perform(post("/api/v1/work-orders/{id}/reassignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","reassignmentReason":"CUSTOMER_REQUEST"}
                                """.formatted(techThird)))
                .andExpect(status().isOk());

        // Three assignment rows in total
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ?",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(total).isEqualTo(3);

        // Exactly one active (end_at IS NULL)
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND end_at IS NULL",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(active).isEqualTo(1);

        // Two superseded
        Integer superseded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND end_at IS NOT NULL",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(superseded).isEqualTo(2);

        // Two reassignment reasons recorded (SLA_RISK and CUSTOMER_REQUEST)
        Integer withReason = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND reassignment_reason IS NOT NULL",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(withReason).isEqualTo(2);

        // Supersede links form a chain: each superseded row has superseded_by set
        Integer withSupersededBy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND superseded_by IS NOT NULL",
                Integer.class, WO_ASSIGNED_ID);
        assertThat(withSupersededBy).isEqualTo(2);
    }

    // ── Stub EligibilityService ───────────────────────────────────────────────

    @TestConfiguration
    static class StubEligibility {

        @Bean
        @Primary
        EligibilityService eligibilityService() {
            return new EligibilityService(null, null, null) {
                @Override
                public EligibilityResult evaluate(WorkOrderRequirements requirements) {
                    return new EligibilityResult(
                            List.of(
                                    TECH_ELIGIBLE,
                                    TECH_CURRENT,
                                    // Any third technician ID is eligible
                                    UUID.fromString("00000000-0000-7139-8000-000000000030")
                            ),
                            List.of(new ExcludedCandidate(
                                    TECH_INELIGIBLE, ExclusionReason.CERTIFICATION_MISSING)),
                            false);
                }
            };
        }
    }
}

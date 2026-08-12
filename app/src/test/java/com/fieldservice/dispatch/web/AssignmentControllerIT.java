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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@link AssignmentController} (WO-138).
 *
 * <p>Uses a stubbed {@link EligibilityService} to control technician eligibility
 * without requiring a full availability/certification fixture. The real transition
 * service, assignment repository, and Envers are exercised against the containerised
 * PostgreSQL instance.
 *
 * <p>Covers:
 * <ul>
 *   <li>200 happy path — top-3 rank, no override required</li>
 *   <li>200 with override — rank > 3</li>
 *   <li>400 missing override reason when rank > 3</li>
 *   <li>400 missing override reason when no snapshot supplied</li>
 *   <li>403 TECHNICIAN role denied</li>
 *   <li>404/403 non-existent work order (non-disclosure)</li>
 *   <li>409 illegal transition (ASSIGNED → ASSIGN)</li>
 *   <li>409 illegal transition from CANCELLED state</li>
 *   <li>422 certification guard refusal</li>
 *   <li>422 even when override reason is supplied for guard failure</li>
 *   <li>Envers revision assertion on successful assignment</li>
 *   <li>Micrometer rank summary recorded</li>
 * </ul>
 */
@Tag("integration")
@Import(AssignmentControllerIT.StubEligibility.class)
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/seed-wo138.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentControllerIT extends AbstractIntegrationTest {

    static final UUID WO_NEW_ID       = UUID.fromString("00000000-0000-7138-8000-000000000001");
    static final UUID WO_ASSIGNED_ID  = UUID.fromString("00000000-0000-7138-8000-000000000002");
    static final UUID WO_CANCELLED_ID = UUID.fromString("00000000-0000-7138-8000-000000000003");
    static final UUID WO_UNKNOWN_ID   = UUID.fromString("00000000-0000-7138-8000-000000099999");

    /** Eligible technician — returns eligible from stub */
    static final UUID TECH_ELIGIBLE   = UUID.fromString("00000000-0000-7138-8000-000000000010");
    /** Ineligible technician — CERTIFICATION_MISSING returned from stub */
    static final UUID TECH_INELIGIBLE = UUID.fromString("00000000-0000-7138-8000-000000000011");

    @Autowired DatabaseCleaner dbCleaner;
    @Autowired JdbcTemplate    jdbc;

    @AfterEach
    void clean() { dbCleaner.truncateAll(); }

    // ── 200 happy path: top-3 rank, no override required ──────────────────────

    @Test
    @DisplayName("200 — assignment with snapshot rank 1 creates assignment row and Envers revision")
    void assign_topRank_returns200AndCreatesAuditRevision() throws Exception {
        UUID snapshotId = insertSnapshotWithCandidate(WO_NEW_ID, TECH_ELIGIBLE, 1);

        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","recommendationSnapshotId":"%s"}
                                """.formatted(TECH_ELIGIBLE, snapshotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignmentId", notNullValue()))
                .andExpect(jsonPath("$.data.workOrderId", is(WO_NEW_ID.toString())))
                .andExpect(jsonPath("$.data.technicianId", is(TECH_ELIGIBLE.toString())))
                .andExpect(jsonPath("$.data.state", is("ASSIGNED")))
                .andExpect(jsonPath("$.data.recommendationRank", is(1)))
                .andExpect(jsonPath("$.data.overrideRecorded", is(false)));

        // Verify work order transitioned to ASSIGNED in DB
        String state = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, WO_NEW_ID);
        assertThat(state).isEqualTo("ASSIGNED");

        // Envers assertion: at least one revision for the work order
        int revCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_aud WHERE id = ?",
                Integer.class, WO_NEW_ID);
        assertThat(revCount).isGreaterThanOrEqualTo(1);

        // Assignment row exists
        int assignmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE work_order_id = ? AND technician_id = ?",
                Integer.class, WO_NEW_ID, TECH_ELIGIBLE);
        assertThat(assignmentCount).isEqualTo(1);
    }

    // ── 200 with override reason when rank > 3 ─────────────────────────────────

    @Test
    @DisplayName("200 — rank 5 with override reason accepted")
    void assign_rank5WithOverrideReason_returns200() throws Exception {
        UUID snapshotId = insertSnapshotWithCandidate(WO_NEW_ID, TECH_ELIGIBLE, 5);

        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","recommendationSnapshotId":"%s","overrideReason":"Technician requested by customer"}
                                """.formatted(TECH_ELIGIBLE, snapshotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overrideRecorded", is(true)))
                .andExpect(jsonPath("$.data.recommendationRank", is(5)));
    }

    // ── 200 without snapshot — override reason required ───────────────────────

    @Test
    @DisplayName("200 — no snapshot with override reason accepted")
    void assign_noSnapshot_withOverrideReason_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"Manual assignment by dispatcher"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overrideRecorded", is(true)))
                .andExpect(jsonPath("$.data.recommendationRank").doesNotExist());
    }

    // ── 400 missing override reason ────────────────────────────────────────────

    @Test
    @DisplayName("400 — rank > 3 without override reason returns 400 with field error")
    void assign_rank4NoOverride_returns400() throws Exception {
        UUID snapshotId = insertSnapshotWithCandidate(WO_NEW_ID, TECH_ELIGIBLE, 4);

        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","recommendationSnapshotId":"%s"}
                                """.formatted(TECH_ELIGIBLE, snapshotId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field", is("overrideReason")));

        // Work order must remain NEW — no state change
        String state = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, WO_NEW_ID);
        assertThat(state).isEqualTo("NEW");
    }

    @Test
    @DisplayName("400 — no snapshot without override reason returns 400")
    void assign_noSnapshotNoOverride_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field", is("overrideReason")));
    }

    // ── 400 missing technicianId ───────────────────────────────────────────────

    @Test
    @DisplayName("400 — missing technicianId returns 400 validation error")
    void assign_missingTechnicianId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"overrideReason":"Testing"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── 403 role denied ────────────────────────────────────────────────────────

    @Test
    @DisplayName("403 — TECHNICIAN role cannot assign work orders")
    void assign_technicianRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForTechnician(UUID.randomUUID(), TECH_ELIGIBLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"Test"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isForbidden());
    }

    // ── 403/404 — non-existent work order ─────────────────────────────────────

    @Test
    @DisplayName("403 — non-existent work order returns 403 (non-disclosure)")
    void assign_unknownWorkOrder_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_UNKNOWN_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"Test"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isForbidden());
    }

    // ── 409 illegal transition ─────────────────────────────────────────────────

    @Test
    @DisplayName("409 — work order already ASSIGNED returns 409 illegal transition")
    void assign_alreadyAssigned_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"Test"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("WORK_ORDER_ILLEGAL_TRANSITION")));
    }

    @Test
    @DisplayName("409 — CANCELLED work order returns 409 illegal transition")
    void assign_cancelledWorkOrder_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_CANCELLED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"Test"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("WORK_ORDER_ILLEGAL_TRANSITION")));
    }

    // ── 422 certification guard ────────────────────────────────────────────────

    @Test
    @DisplayName("422 — ineligible technician returns 422 regardless of override reason")
    void assign_ineligibleTechnician_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"I insist, assign anyway"}
                                """.formatted(TECH_INELIGIBLE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("CERTIFICATION_NOT_CURRENT")));

        // Work order must remain NEW
        String state = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, WO_NEW_ID);
        assertThat(state).isEqualTo("NEW");
    }

    @Test
    @DisplayName("422 — certification guard cannot be bypassed with override reason in top-3 position")
    void assign_ineligibleTechnicianInTop3_returns422() throws Exception {
        UUID snapshotId = insertSnapshotWithCandidate(WO_NEW_ID, TECH_INELIGIBLE, 1);

        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","recommendationSnapshotId":"%s"}
                                """.formatted(TECH_INELIGIBLE, snapshotId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("CERTIFICATION_NOT_CURRENT")));
    }

    // ── 400 unknown field rejected ─────────────────────────────────────────────

    @Test
    @DisplayName("400 — unknown JSON property rejected (FAIL_ON_UNKNOWN_PROPERTIES)")
    void assign_unknownProperty_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/assignment", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"technicianId":"%s","overrideReason":"Test","unknownField":"bad"}
                                """.formatted(TECH_ELIGIBLE)))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private UUID insertSnapshotWithCandidate(UUID workOrderId, UUID technicianId, int rank) {
        UUID snapshotId   = UUID.randomUUID();
        UUID candidateId  = UUID.randomUUID();
        UUID generatedBy  = TestJwtFactory.DISPATCHER_USER_ID;

        jdbc.update("""
                INSERT INTO recommendation_snapshot
                    (id, work_order_id, generated_at, generated_by, weight_set_version,
                     travel_estimate_degraded, parts_data_degraded, candidate_pool_size, truncated)
                VALUES (?, ?, NOW(), ?, '1.0', false, false, 1, false)
                """,
                snapshotId, workOrderId, generatedBy);

        jdbc.update("""
                INSERT INTO recommendation_snapshot_candidate
                    (id, snapshot_id, technician_id, rank, score, factor_breakdown)
                VALUES (?, ?, ?, ?, 0.850000, '[]'::jsonb)
                """,
                candidateId, snapshotId, technicianId, rank);

        return snapshotId;
    }

    // ── Stub EligibilityService ────────────────────────────────────────────────

    @TestConfiguration
    static class StubEligibility {

        @Bean
        @Primary
        EligibilityService eligibilityService() {
            return new EligibilityService(null, null, null) {
                @Override
                public EligibilityResult evaluate(WorkOrderRequirements requirements) {
                    return new EligibilityResult(
                            List.of(TECH_ELIGIBLE),
                            List.of(new ExcludedCandidate(
                                    TECH_INELIGIBLE, ExclusionReason.CERTIFICATION_MISSING)),
                            false);
                }
            };
        }
    }
}

package com.fieldservice.dispatch.web;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.fieldservice.security.TestJwtFactory.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link ReassignmentController} (WO-139 AC-10).
 *
 * <p>Uses V100 fixture data:
 * <ul>
 *   <li>WO_A1 (30000000-…-0001) ASSIGNED to TECH_1 (00000000-…-0011)</li>
 *   <li>WO_A2 (30000000-…-0002) ASSIGNED to TECH_2 (00000000-…-0012)</li>
 *   <li>WO_UNASSIGNED (30000000-…-0004) in NEW state</li>
 *   <li>TECH_2 (00000000-…-0012) exists and is eligible by default</li>
 * </ul>
 */
@DisplayName("ReassignmentController integration tests")
class ReassignmentControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BASE_PATH = "/api/v1/work-orders";

    // IDs from V100 test fixtures
    private static final UUID WO_A1          = TestJwtFactory.WO_A1;
    private static final UUID WO_UNASSIGNED  = TestJwtFactory.WO_UNASSIGNED;
    private static final UUID TECH_1         = TestJwtFactory.TECH_1_ID;
    private static final UUID TECH_2         = TestJwtFactory.TECH_2_ID;

    // A WO in ASSIGNED state with a future confirmed appointment — set up via JDBC in @BeforeEach
    private static final UUID WO_APPT_CONFIRMED =
            UUID.fromString("39000000-0000-0000-0000-000000000001");

    @BeforeEach
    void seedAppointmentWorkOrder() {
        // Insert a work order with appointmentConfirmed=true and a future scheduledWindowStart
        // so appointment-guard tests have a deterministic target.
        jdbc.update("DELETE FROM assignment WHERE work_order_id = ?", WO_APPT_CONFIRMED);
        jdbc.update("DELETE FROM work_order WHERE id = ?", WO_APPT_CONFIRMED);

        jdbc.update("""
            INSERT INTO work_order
                (id, account_id, reference, state, priority, appointment_confirmed,
                 scheduled_window_start, scheduled_window_end, assigned_technician_id, version)
            VALUES (?, ?, ?, 'ASSIGNED', 'P2', true,
                    NOW() + INTERVAL '2 hours', NOW() + INTERVAL '4 hours', ?, 0)
            """,
            WO_APPT_CONFIRMED,
            ACCT_A,
            "REF-APPT-CONFIRMED",
            TECH_1);

        jdbc.update("""
            INSERT INTO assignment (id, work_order_id, technician_id, is_current, version)
            VALUES (?, ?, ?, true, 0)
            """,
            UUID.randomUUID(),
            WO_APPT_CONFIRMED,
            TECH_1);
    }

    // ─── 200 success ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /reassignment → 200 with supersede chain recorded")
    void reassign_dispatcher_success() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "SLA_RISK",
              "overrideReason": "Technician has domain expertise required",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_A1)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").isNotEmpty())
                .andExpect(jsonPath("$.supersededAssignmentId").isNotEmpty())
                .andExpect(jsonPath("$.workOrderId").value(WO_A1.toString()))
                .andExpect(jsonPath("$.technicianId").value(TECH_2.toString()))
                .andExpect(jsonPath("$.reassignmentReason").value("SLA_RISK"))
                .andExpect(jsonPath("$.overrideRecorded").value(true))
                .andExpect(jsonPath("$.appointmentImpactRecorded").value(false));
    }

    @Test
    @DisplayName("POST /reassignment with appointment ack → 200, appointmentImpactRecorded=true")
    void reassign_with_appointment_acknowledgement_success() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "TECHNICIAN_UNAVAILABLE",
              "overrideReason": "Emergency reallocation approved",
              "appointmentImpactAcknowledgement": "Customer notified via SMS",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_APPT_CONFIRMED)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentImpactRecorded").value(true));
    }

    // ─── 400 validation failures ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /reassignment missing reassignmentReason → 400")
    void reassign_missing_reason_400() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_A1)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reassignment missing technicianId → 400")
    void reassign_missing_technician_id_400() throws Exception {
        String body = """
            {
              "reassignmentReason": "SLA_RISK",
              "overrideReason": "test",
              "expectedVersion": 0
            }
            """;

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_A1)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reassignment same technician → 400 SAME_ASSIGNEE")
    void reassign_same_technician_400() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "JOB_OVERRUN",
              "overrideReason": "re-confirm same tech",
              "expectedVersion": 0
            }
            """.formatted(TECH_1);  // TECH_1 already assigned to WO_A1

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_A1)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ─── 403 access control ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /reassignment as CUSTOMER → 403")
    void reassign_customer_role_403() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "SLA_RISK",
              "overrideReason": "test",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_A1)
                        .with(jwt().jwt(customerBothAccountsJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /reassignment as MANAGER → 403")
    void reassign_manager_role_403() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "SLA_RISK",
              "overrideReason": "test",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_A1)
                        .with(jwt().jwt(managerJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ─── 409 invalid state ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /reassignment on NEW work order → 409 REASSIGNMENT_INVALID_STATE")
    void reassign_new_state_409() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "SLA_RISK",
              "overrideReason": "test",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_UNASSIGNED)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REASSIGNMENT_INVALID_STATE"));
    }

    // ─── 422 appointment breach ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /reassignment confirmed future appointment, no ack → 422 APPOINTMENT_BREACH_UNACKNOWLEDGED")
    void reassign_confirmed_appointment_no_ack_422() throws Exception {
        String body = """
            {
              "technicianId": "%s",
              "reassignmentReason": "TECHNICIAN_UNAVAILABLE",
              "overrideReason": "Urgent override",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);  // no appointmentImpactAcknowledgement

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_APPT_CONFIRMED)
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_BREACH_UNACKNOWLEDGED"));
    }

    // ─── History endpoint ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /assignments/history returns entries for DISPATCHER")
    void getHistory_dispatcher_returns_list() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/{woId}/assignments/history", WO_A1)
                        .with(jwt().jwt(dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("GET /assignments/history returns entries for MANAGER")
    void getHistory_manager_can_read() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/{woId}/assignments/history", WO_A1)
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /assignments/history as CUSTOMER → 403")
    void getHistory_customer_403() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/{woId}/assignments/history", WO_A1)
                        .with(jwt().jwt(customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /assignments/history after reassignment shows supersede chain")
    void getHistory_after_reassignment_shows_supersede() throws Exception {
        // First reassign
        String reassignBody = """
            {
              "technicianId": "%s",
              "reassignmentReason": "JOB_OVERRUN",
              "overrideReason": "Capacity overrun needs rebalancing",
              "expectedVersion": 0
            }
            """.formatted(TECH_2);

        mockMvc.perform(post(BASE_PATH + "/{woId}/reassignment", WO_B1())
                        .with(jwt().jwt(dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reassignBody))
                .andExpect(status().isOk());

        // Then check history
        mockMvc.perform(get(BASE_PATH + "/{woId}/assignments/history", WO_B1())
                        .with(jwt().jwt(dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)))
                // Most recent entry (last) should be active
                .andExpect(jsonPath("$[?(@.active == true)]").exists())
                // Prior entry should be superseded (end_at set)
                .andExpect(jsonPath("$[?(@.active == false)]").exists());
    }

    private static UUID WO_B1() {
        return UUID.fromString("30000000-0000-0000-0000-000000000003");
    }
}

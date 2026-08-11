package com.fieldservice.workorder.lifecycle.guards;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for guarded lifecycle transitions (WO-125, AC-10).
 *
 * <p>Uses {@link AbstractIntegrationTest} (Testcontainers PostgreSQL + fixtures V100+V106).
 * Asserts HTTP 422 with specific guard sub-codes and verifies persisted state is unchanged.
 */
@DisplayName("Guard integration tests: guarded transitions assert 422 with sub-codes")
class GuardTransitionIntegrationTest extends AbstractIntegrationTest {

    private static final String TRANSITIONS_URL = "/api/v1/work-orders/{id}/transitions";

    // Fixture IDs from V106__guard_fixtures.sql
    private static final String WO_IN_PROGRESS_NO_LABOUR    = "3a000000-0000-0000-0000-000000000002";
    private static final String WO_COMPLETED_UNRECONCILED   = "3a000000-0000-0000-0000-000000000003";
    private static final String WO_NEW_WITH_COMPETENCY      = "3a000000-0000-0000-0000-000000000006";
    private static final String WO_IN_PROGRESS_WITH_LABOUR  = "3a000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("COMPLETE without labour time returns 422 with WORK_ORDER_GUARD_REFUSED and labour message")
    void complete_withoutLabourTime_returns422() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_IN_PROGRESS_NO_LABOUR)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"COMPLETE\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.message", containsString("labour time")));
    }

    @Test
    @DisplayName("CLOSE with unreconciled parts returns 422 with WORK_ORDER_GUARD_REFUSED and reconcile message")
    void close_withUnreconciledParts_returns422() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_COMPLETED_UNRECONCILED)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"CLOSE\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.message", containsString("reconcil")));
    }

    @Test
    @DisplayName("ASSIGN to technician without required cert returns 422 with CERTIFICATION_EXPIRED message")
    void assign_withoutRequiredCert_returns422() throws Exception {
        // TECH_2 has no certifications; wo_guard_new_with_competency requires HVAC_CERT.
        // Set assignedTechnicianId to TECH_2 before firing ASSIGN.
        jdbc.update(
                "UPDATE work_order SET assigned_technician_id = '00000000-0000-0000-0000-000000000012' " +
                "WHERE id = '3a000000-0000-0000-0000-000000000006'");

        mockMvc.perform(post(TRANSITIONS_URL, WO_NEW_WITH_COMPETENCY)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.message", containsString("HVAC_CERT")));
    }

    @Test
    @DisplayName("HOLD without holdReasonCode returns 422 with HOLD_REASON_MISSING message")
    void hold_withoutHoldReasonCode_returns422() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_IN_PROGRESS_WITH_LABOUR)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.message", containsString("hold reason")));
    }

    @Test
    @DisplayName("guard refusal leaves persisted state unchanged")
    void guardRefusal_doesNotMutateState() throws Exception {
        String stateBefore = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, WO_IN_PROGRESS_NO_LABOUR);

        mockMvc.perform(post(TRANSITIONS_URL, WO_IN_PROGRESS_NO_LABOUR)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"COMPLETE\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity());

        String stateAfter = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, WO_IN_PROGRESS_NO_LABOUR);
        assertThat(stateAfter).isEqualTo(stateBefore);
    }

    @Test
    @DisplayName("certification guard cannot be bypassed by ADMIN role — refused with 422")
    void certGuard_notBypassableByAdmin() throws Exception {
        jdbc.update(
                "UPDATE work_order SET assigned_technician_id = '00000000-0000-0000-0000-000000000012' " +
                "WHERE id = '3a000000-0000-0000-0000-000000000006'");

        mockMvc.perform(post(TRANSITIONS_URL, WO_NEW_WITH_COMPETENCY)
                        .with(jwt()
                                .jwt(TestJwtFactory.adminJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"));
    }
}

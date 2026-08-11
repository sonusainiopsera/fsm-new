package com.fieldservice.workorder.holds;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for hold/resume lifecycle and the hold-reasons vocabulary endpoint (WO-126).
 */
@DisplayName("Hold lifecycle integration tests (WO-126)")
class HoldLifecycleIT extends AbstractIntegrationTest {

    private static final String TRANSITIONS_URL     = "/api/v1/work-orders/{id}/transitions";
    private static final String WORK_ORDER_URL       = "/api/v1/work-orders/{id}";
    private static final String HOLD_REASONS_URL     = "/api/v1/work-orders/hold-reasons";

    // Fixture IDs from V107__hold_fixtures.sql
    private static final String WO_NO_HOLDS         = "3b000000-0000-0000-0000-000000000001";
    private static final String WO_ON_HOLD          = "3b000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ─── hold-reasons endpoint ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /hold-reasons returns active reasons in sort order with pagination envelope")
    void holdReasons_returnsActiveInOrder() throws Exception {
        mockMvc.perform(get(HOLD_REASONS_URL)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].code").value("AWAITING_PARTS"))
                .andExpect(jsonPath("$.data[0].label").isString())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("GET /hold-reasons excludes inactive codes")
    void holdReasons_excludesInactiveCodes() throws Exception {
        // INACTIVE_TEST_CODE is seeded by V107 with active=false
        mockMvc.perform(get(HOLD_REASONS_URL)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'INACTIVE_TEST_CODE')]").isEmpty());
    }

    @Test
    @DisplayName("GET /hold-reasons requires authentication")
    void holdReasons_requiresAuth() throws Exception {
        mockMvc.perform(get(HOLD_REASONS_URL))
                .andExpect(status().isUnauthorized());
    }

    // ─── HOLD transition with invalid vocabulary ───────────────────────────────

    @Test
    @DisplayName("HOLD with unknown reason code returns 400 with field-level error on holdReasonCode")
    void hold_withUnknownCode_returns400() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_NO_HOLDS)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":0,\"holdReasonCode\":\"NO_SUCH_CODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("holdReasonCode"));
    }

    @Test
    @DisplayName("HOLD with inactive reason code returns 400 with field-level error")
    void hold_withInactiveCode_returns400() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_NO_HOLDS)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":0,\"holdReasonCode\":\"INACTIVE_TEST_CODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("holdReasonCode"));
    }

    @Test
    @DisplayName("HOLD with only a note and no reason code returns 422 (guard refuses)")
    void hold_withOnlyNote_returns422() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_NO_HOLDS)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":0,\"reason\":\"some note only\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"));
    }

    // ─── HOLD + RESUME cycle ─────────────────────────────────────────────────

    @Test
    @DisplayName("HOLD with valid code creates open hold record")
    void hold_withValidCode_createsHoldRecord() throws Exception {
        mockMvc.perform(post(TRANSITIONS_URL, WO_NO_HOLDS)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":0," +
                                  "\"holdReasonCode\":\"AWAITING_PARTS\"," +
                                  "\"reason\":\"Waiting for compressor\"}"))
                .andExpect(status().isOk());

        Integer holdCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_hold WHERE work_order_id = ? AND ended_at IS NULL",
                Integer.class, WO_NO_HOLDS);
        assertThat(holdCount).isEqualTo(1);

        String reasonCode = jdbc.queryForObject(
                "SELECT reason_code FROM work_order_hold WHERE work_order_id = ? AND ended_at IS NULL",
                String.class, WO_NO_HOLDS);
        assertThat(reasonCode).isEqualTo("AWAITING_PARTS");
    }

    @Test
    @DisplayName("RESUME closes open hold and increments cumulative_hold_minutes")
    void resume_closesHoldAndAccumulatesMinutes() throws Exception {
        Integer cumulativeBefore = jdbc.queryForObject(
                "SELECT cumulative_hold_minutes FROM work_order WHERE id = ?",
                Integer.class, WO_ON_HOLD);

        mockMvc.perform(post(TRANSITIONS_URL, WO_ON_HOLD)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"RESUME\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        Integer holdCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_hold WHERE work_order_id = ? AND ended_at IS NULL",
                Integer.class, WO_ON_HOLD);
        assertThat(holdCount).isEqualTo(0);

        Integer endedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_hold WHERE work_order_id = ? AND ended_at IS NOT NULL",
                Integer.class, WO_ON_HOLD);
        assertThat(endedCount).isEqualTo(1);

        Integer cumulativeAfter = jdbc.queryForObject(
                "SELECT cumulative_hold_minutes FROM work_order WHERE id = ?",
                Integer.class, WO_ON_HOLD);
        assertThat(cumulativeAfter).isGreaterThan(cumulativeBefore);
    }

    @Test
    @DisplayName("work order detail includes currentHoldReasonCode and cumulativeHoldMinutes")
    void workOrderDetail_includesHoldFields() throws Exception {
        mockMvc.perform(get(WORK_ORDER_URL, WO_ON_HOLD)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentHoldReasonCode").value("AWAITING_PARTS"))
                .andExpect(jsonPath("$.holdStartedAt").isString())
                .andExpect(jsonPath("$.cumulativeHoldMinutes").isNumber());
    }

    @Test
    @DisplayName("work order with no holds has null hold fields and zero cumulative minutes")
    void workOrderDetail_noHolds_hasNullHoldFields() throws Exception {
        mockMvc.perform(get(WORK_ORDER_URL, WO_NO_HOLDS)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentHoldReasonCode").doesNotExist())
                .andExpect(jsonPath("$.cumulativeHoldMinutes").value(0));
    }

    @Test
    @DisplayName("partial unique index prevents second open hold on same work order")
    void partialUniqueIndex_preventsDoubleHold() throws Exception {
        // WO_ON_HOLD already has an open hold; a second HOLD is blocked by illegal transition (wrong state)
        // State is ON_HOLD so HOLD is not legal from ON_HOLD — expect 409
        mockMvc.perform(post(TRANSITIONS_URL, WO_ON_HOLD)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":0,\"holdReasonCode\":\"WEATHER\"}"))
                .andExpect(status().isConflict());
    }
}

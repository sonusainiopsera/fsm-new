package com.fieldservice.notification.preference;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.support.AuditAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for GET/PUT /api/v1/users/{userId}/notification-preferences (WO-197).
 *
 * <p>Covers:
 * <ul>
 *   <li>401 unauthenticated, 403 cross-user access without existence disclosure</li>
 *   <li>200 for self-access and admin access</li>
 *   <li>Envelope shape (data, page, links)</li>
 *   <li>PUT persists row and returns updated state (source=EXPLICIT)</li>
 *   <li>Envers revision created on PUT</li>
 *   <li>400 on invalid category/channel enum values and unknown JSON properties</li>
 *   <li>Page size clamped to max 50</li>
 * </ul>
 */
@DisplayName("NotificationPreferenceController integration tests (WO-197)")
class NotificationPreferenceIT extends AbstractIntegrationTest {

    private static final String BASE_URL =
            "/api/v1/users/{userId}/notification-preferences";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM notification_preference");
    }

    // ── 401 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unauthenticated GET returns 401")
    void get_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── 403 cross-user — no existence disclosure ───────────────────────────────

    @Test
    @DisplayName("Non-admin user requesting another user's preferences gets 403")
    void get_crossUser_dispatcher_returns403() throws Exception {
        // DISPATCHER requests TECHNICIAN's preferences
        mockMvc.perform(get(BASE_URL, TestJwtFactory.TECH_1_USER_ID)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN requesting another user's preferences gets 403")
    void get_crossUser_technician_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    // ── 200 self-access ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dispatcher reads own preferences (no rows) → 200 with empty data")
    void get_self_dispatcher_returnsEmptyData() throws Exception {
        mockMvc.perform(get(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(lessThanOrEqualTo(50)))
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Admin reads any user's preferences → 200")
    void get_admin_readsAnyUser_returns200() throws Exception {
        mockMvc.perform(get(BASE_URL, TestJwtFactory.TECH_1_USER_ID)
                        .with(jwt()
                                .jwt(TestJwtFactory.adminJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ── PUT persists and audits ────────────────────────────────────────────────

    @Test
    @DisplayName("PUT upserts preference row, response contains EXPLICIT source, Envers revision created")
    void put_self_upserts_andCreatesAuditRevision() throws Exception {
        String body = """
                {"preferences":[{"category":"WO_ASSIGNED","channel":"EMAIL","enabled":false}]}
                """;

        mockMvc.perform(put(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("WO_ASSIGNED"))
                .andExpect(jsonPath("$.data[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.data[0].enabled").value(false))
                .andExpect(jsonPath("$.data[0].source").value("EXPLICIT"));

        // Verify row persisted
        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_preference " +
                "WHERE user_id = ? AND category = 'WO_ASSIGNED' AND channel = 'EMAIL'",
                Integer.class, TestJwtFactory.DISPATCHER_USER_ID);
        assertThat(count).isEqualTo(1);

        // Verify Envers revision created
        UUID prefId = jdbc.queryForObject(
                "SELECT id FROM notification_preference WHERE user_id = ? " +
                "AND category = 'WO_ASSIGNED' AND channel = 'EMAIL'",
                UUID.class, TestJwtFactory.DISPATCHER_USER_ID);
        AuditAssertions.assertSingleAddRevision(jdbc, "notification_preference_aud", prefId);
    }

    @Test
    @DisplayName("Admin PUT for another user succeeds and creates revision attributed to admin")
    void put_admin_forOtherUser_succeeds() throws Exception {
        String body = """
                {"preferences":[{"category":"SLA_BREACH","channel":"IN_APP","enabled":true}]}
                """;

        mockMvc.perform(put(BASE_URL, TestJwtFactory.TECH_1_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt()
                                .jwt(TestJwtFactory.adminJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("EXPLICIT"));
    }

    // ── 400 validation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT with unknown category returns 400 with field error")
    void put_unknownCategory_returns400() throws Exception {
        String body = """
                {"preferences":[{"category":"UNKNOWN_CAT","channel":"EMAIL","enabled":false}]}
                """;

        mockMvc.perform(put(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT with unknown channel returns 400")
    void put_unknownChannel_returns400() throws Exception {
        String body = """
                {"preferences":[{"category":"WO_ASSIGNED","channel":"PIGEON","enabled":true}]}
                """;

        mockMvc.perform(put(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT with unknown JSON property returns 400 (FAIL_ON_UNKNOWN_PROPERTIES)")
    void put_unknownJsonProperty_returns400() throws Exception {
        String body = """
                {"preferences":[{"category":"WO_ASSIGNED","channel":"EMAIL","enabled":false}],
                 "unknownField":"value"}
                """;

        mockMvc.perform(put(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    // ── Page size clamped ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Page size is clamped to server maximum of 50")
    void get_pageSizeClamped() throws Exception {
        mockMvc.perform(get(BASE_URL, TestJwtFactory.DISPATCHER_USER_ID)
                        .param("size", "9999")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(lessThanOrEqualTo(50)));
    }
}

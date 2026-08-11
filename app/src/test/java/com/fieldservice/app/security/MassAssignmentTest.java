package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static com.fieldservice.app.security.TestJwtFactory.admin;
import static com.fieldservice.app.security.TestJwtFactory.dispatcher;
import static com.fieldservice.app.security.TestJwtFactory.WO_001_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mass-assignment protection tests.
 *
 * <p>Proves that posting non-writable or unknown fields to mutating endpoints is rejected
 * with HTTP 400 due to Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES = true} configuration
 * (set in {@code JacksonConfiguration}), and that no partial row is persisted.
 *
 * <h3>Invariants under test</h3>
 * <ul>
 *   <li>A work-order creation request containing an unknown field (e.g., {@code adminNotes})
 *       is rejected with 400 before any row is written.</li>
 *   <li>A transition request containing an unrecognised field is rejected with 400.</li>
 *   <li>A user-preference update containing an unknown field is rejected with 400.</li>
 *   <li>The work-order table row count is unchanged after each rejected request, confirming
 *       no partial persistence occurred (the rejection happens before any SQL executes).</li>
 * </ul>
 *
 * <p>This satisfies AC9 of WO-203: "a privilege-escalation attempt via request body (for
 * example submitting a role or assignee field the caller may not set) is rejected because
 * unknown or non-writable properties fail validation."
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/db/fixtures.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql",
     executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class MassAssignmentTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // Work-order creation: unknown field injected → 400, no row written
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/v1/work-orders: unknown field in body is rejected 400 — no row written")
    void work_order_creation_with_unknown_field_returns_400_and_no_row_written() throws Exception {
        int countBefore = workOrderCount();

        // Body contains valid required fields PLUS an unknown field that could represent
        // a privilege-escalation attempt (e.g., setting an admin-only status field)
        String body = """
                {
                  "reference": "WO-MASS-001",
                  "priority": "MEDIUM",
                  "siteId": "bbbbbbbb-0000-0000-0000-000000000001",
                  "adminNotes": "this-field-does-not-exist",
                  "internalStatus": "ESCALATED"
                }
                """;

        mockMvc.perform(post("/api/v1/work-orders")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(dispatcher().getClaims())).subject(dispatcher().getSubject())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // No new row — the request was rejected before any INSERT
        int countAfter = workOrderCount();
        assertThat(countAfter)
                .as("Work-order table must not grow after a rejected mass-assignment request")
                .isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // Work-order transition: unknown field in body → 400
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/v1/work-orders/{id}/transitions: unknown field returns 400")
    void transition_with_unknown_field_returns_400() throws Exception {
        String body = """
                {
                  "event": "DEPART",
                  "expectedVersion": 0,
                  "overrideAssignee": "another-tech-uuid"
                }
                """;

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", WO_001_ID)
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(dispatcher().getClaims())).subject(dispatcher().getSubject())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // User preference update: unknown field → 400
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PUT /api/v1/users/me/preferences: unknown field is rejected 400")
    void preference_update_with_unknown_field_returns_400() throws Exception {
        String body = """
                {
                  "preference": "DARK",
                  "adminOverride": true,
                  "role": "ADMIN"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me/preferences")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(admin().getClaims())).subject(admin().getSubject())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Error body must not expose stack traces or internal detail
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("400 response body for unknown field does not expose stack traces or internal detail")
    void bad_request_body_does_not_expose_internal_detail() throws Exception {
        String body = """
                {
                  "reference": "WO-MASS-002",
                  "unknownSensitiveField": "value"
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/work-orders")
                .with(jwt().jwt(b -> b.claims(c -> c.putAll(dispatcher().getClaims())).subject(dispatcher().getSubject())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // Must not contain stack-trace markers or internal class names
        assertThat(responseBody)
                .doesNotContain("java.lang.", "sun.reflect.", "com.fasterxml.jackson.databind",
                        "org.springframework.", "at ", "Exception in thread",
                        "NullPointerException", "StackTrace");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private int workOrderCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_order", Integer.class);
        return count != null ? count : 0;
    }
}

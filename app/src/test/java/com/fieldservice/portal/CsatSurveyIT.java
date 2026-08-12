package com.fieldservice.portal;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CSAT survey portal endpoints.
 *
 * <p>Covers: list surveys (200), submit response (201), duplicate (409),
 * expired window (422), foreign survey (404), wrong role (403), unauthenticated (401).
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/seed-wo172.sql",
        "classpath:fixtures/seed-wo173.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CsatSurveyIT extends AbstractIntegrationTest {

    static final UUID USER_ACME_ID = UUID.fromString("00000000-0000-7019-8000-000000000001");
    static final UUID ACME_ID      = UUID.fromString("00000000-0000-7012-8000-000000000001");
    static final UUID USER_BLUE_ID = UUID.fromString("00000000-0000-7019-8000-000000000002");
    static final UUID BLUE_ID      = UUID.fromString("00000000-0000-7012-8000-000000000002");

    // Survey UUIDs defined in seed-wo173.sql
    static final UUID SURVEY_PENDING_ACME  = UUID.fromString("00000000-0000-7173-8000-000000000001");
    static final UUID SURVEY_ANSWERED_ACME = UUID.fromString("00000000-0000-7173-8000-000000000002");
    static final UUID SURVEY_EXPIRED_ACME  = UUID.fromString("00000000-0000-7173-8000-000000000003");
    static final UUID SURVEY_PENDING_BLUE  = UUID.fromString("00000000-0000-7173-8000-000000000004");

    @Autowired DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/portal/surveys
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET surveys returns 200 with standard envelope for authenticated customer")
    void listSurveys_200_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/portal/surveys")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("GET surveys returns only surveys owned by the authenticated account (cross-account isolation)")
    void listSurveys_crossAccountIsolation() throws Exception {
        // Acme user sees only Acme surveys (3 rows), never Bluestone's survey
        mockMvc.perform(get("/api/v1/portal/surveys")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].surveyId",
                        not(hasItem(SURVEY_PENDING_BLUE.toString()))));
    }

    @Test
    @DisplayName("GET surveys returns each survey view with required fields")
    void listSurveys_rowHasRequiredFields() throws Exception {
        mockMvc.perform(get("/api/v1/portal/surveys?size=1")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].surveyId").isString())
                .andExpect(jsonPath("$.data[0].workOrderId").isString())
                .andExpect(jsonPath("$.data[0].issuedAt").isString())
                .andExpect(jsonPath("$.data[0].expiresAt").isString())
                .andExpect(jsonPath("$.data[0].status").isString());
    }

    @Test
    @DisplayName("GET surveys returns 401 for unauthenticated request")
    void listSurveys_401_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/portal/surveys"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET surveys returns 403 for dispatcher role")
    void listSurveys_403_dispatcher() throws Exception {
        mockMvc.perform(get("/api/v1/portal/surveys")
                        .with(jwt().jwt(TestJwtFactory.buildDispatcher())))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/portal/surveys/{id}/response
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST response returns 201 Created for valid PENDING survey")
    void submitResponse_201_happyPath() throws Exception {
        String body = """
                {"score": 5, "npsScore": 9, "comment": "Excellent service"}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_PENDING_ACME)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.surveyId").value(SURVEY_PENDING_ACME.toString()))
                .andExpect(jsonPath("$.submittedAt").isString());
    }

    @Test
    @DisplayName("POST response returns 409 CONFLICT for already-answered survey")
    void submitResponse_409_alreadyAnswered() throws Exception {
        String body = """
                {"score": 4}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_ANSWERED_ACME)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CSAT_ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("POST response returns 422 for expired response window")
    void submitResponse_422_windowExpired() throws Exception {
        String body = """
                {"score": 3}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_EXPIRED_ACME)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CSAT_WINDOW_EXPIRED"));
    }

    @Test
    @DisplayName("POST response returns 404 for survey belonging to different account")
    void submitResponse_404_foreignSurvey() throws Exception {
        String body = """
                {"score": 4}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_PENDING_BLUE)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST response returns 400 for score out of range")
    void submitResponse_400_invalidScore() throws Exception {
        String body = """
                {"score": 6}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_PENDING_ACME)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST response returns 403 for dispatcher role")
    void submitResponse_403_dispatcher() throws Exception {
        String body = """
                {"score": 4}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_PENDING_ACME)
                        .with(jwt().jwt(TestJwtFactory.buildDispatcher()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST response returns 401 for unauthenticated request")
    void submitResponse_401_unauthenticated() throws Exception {
        String body = """
                {"score": 4}
                """;
        mockMvc.perform(post("/api/v1/portal/surveys/{id}/response", SURVEY_PENDING_ACME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}

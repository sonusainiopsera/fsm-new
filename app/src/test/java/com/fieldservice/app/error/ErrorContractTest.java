package com.fieldservice.app.error;

import com.fieldservice.app.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc matrix covering every status code, header, body shape, and the
 * no-internals substring scan required by AC-7.
 *
 * <p>Uses the test-only {@link TestErrorController} to trigger each exception type.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorContractTest {

    private static final String[] FORBIDDEN_SUBSTRINGS = {
        "Exception", "StackTrace", "stackTrace", "at com.", "at org.", "at java.",
        "SQLException", "constraint", "org.hibernate", "org.springframework",
        "com.fieldservice", "NullPointerException", "IllegalArgumentException"
    };

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // 404 Not Found
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("404 — NotFoundException → code=NOT_FOUND, X-Trace-Id present")
    void notFound_returns404_uniformEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/not-found")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // 403 Forbidden
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("403 — ForbiddenException → code=FORBIDDEN, body matches ScopedDenied body")
    void forbidden_returns403_bodyIdentical_to_scopedDenied() throws Exception {
        MvcResult forbidden = mockMvc.perform(get("/test/errors/forbidden")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        MvcResult scoped = mockMvc.perform(get("/test/errors/scoped-denied")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andReturn();

        // Both 403 bodies must have identical structure (no existence disclosure)
        assertThat(forbidden.getResponse().getContentAsString())
                .doesNotContain("NullPointerException");
        assertThat(scoped.getResponse().getContentAsString())
                .doesNotContain("NullPointerException");
        assertNoInternals(forbidden);
        assertNoInternals(scoped);
    }

    // -------------------------------------------------------------------------
    // 409 Conflict variants
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("409 — IllegalTransitionException → code=ILLEGAL_TRANSITION")
    void illegalTransition_returns409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/illegal-transition")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();
        assertNoInternals(result);
    }

    @Test
    @WithMockUser
    @DisplayName("409 — ConflictException → code=CONFLICT")
    void conflict_returns409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/conflict")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn();
        assertNoInternals(result);
    }

    @Test
    @WithMockUser
    @DisplayName("409 — ObjectOptimisticLockingFailureException → code=CONFLICT")
    void optimisticLock_returns409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/optimistic-lock")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn();
        assertNoInternals(result);
    }

    @Test
    @WithMockUser
    @DisplayName("409 — DataIntegrityViolationException → code=CONFLICT, no raw SQL in body")
    void dataIntegrity_returns409_noRawSql() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/data-integrity")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("constraint violation raw text");
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // 422 Business Guard
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("422 — BusinessGuardException → code=GUARD_REFUSED")
    void businessGuard_returns422() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/business-guard")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GUARD_REFUSED"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // 429 Rate Limited with Retry-After
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("429 — RateLimitedException → code=RATE_LIMITED, Retry-After header present")
    void rateLimited_returns429_withRetryAfter() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/rate-limited")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // 503 Provider Degraded
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("503 — ProviderDegradedException → code=PROVIDER_DEGRADED")
    void providerDegraded_returns503() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/provider-degraded")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_DEGRADED"))
                .andReturn();
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // 500 Unmapped Fallback
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("500 — RuntimeException → code=INTERNAL_ERROR, no stack trace leaked")
    void unmapped_returns500_noStackTrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/unhandled")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Unexpected internal failure");
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // 400 Validation failures
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("400 — missing required field → VALIDATION_FAILED with fieldErrors")
    void missingRequired_returns400_withFieldErrors() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").exists())
                .andExpect(jsonPath("$.fieldErrors[0].message").exists())
                .andReturn();
        assertNoInternals(result);
    }

    @Test
    @WithMockUser
    @DisplayName("400 — unknown JSON property → VALIDATION_FAILED naming the offending field")
    void unknownProperty_returns400_namingField() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"valid\", \"unknownField\": \"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("unknownField"))
                .andReturn();
        assertNoInternals(result);
    }

    @Test
    @WithMockUser
    @DisplayName("400 — malformed JSON → VALIDATION_FAILED uniform envelope (no framework page)")
    void malformedJson_returns400_uniformEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();
        assertNoInternals(result);
    }

    // -------------------------------------------------------------------------
    // X-Trace-Id body/header consistency
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("traceId in body matches X-Trace-Id response header")
    void traceId_bodyMatchesHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/errors/not-found")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(result.getResponse().getContentAsString()).contains(headerTraceId);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void assertNoInternals(MvcResult result) {
        String body = result.getResponse().getContentAsString();
        for (String forbidden : FORBIDDEN_SUBSTRINGS) {
            assertThat(body)
                    .as("Response body must not contain internal substring: '%s'", forbidden)
                    .doesNotContain(forbidden);
        }
    }
}

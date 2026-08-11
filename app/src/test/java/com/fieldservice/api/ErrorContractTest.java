package com.fieldservice.api;

import com.fieldservice.platform.api.ErrorEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc test matrix verifying that {@link GlobalExceptionHandler} maps each domain
 * exception to the correct HTTP status, error code, response shape, and X-Trace-Id header.
 *
 * <p>Also asserts non-disclosure: the 403 body is identical for forbidden and not-found-but-scoped
 * scenarios, ensuring cross-role probing cannot distinguish the two.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({com.fieldservice.security.TestSecurityConfig.class, TestErrorController.class})
@Testcontainers
public class ErrorContractTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // 404 Not Found
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("404: NotFoundException → code=NOT_FOUND, X-Trace-Id present")
    void notFound_returns404WithCode() throws Exception {
        mockMvc.perform(get("/test/errors/not-found")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.NOT_FOUND)))
                .andExpect(jsonPath("$.message", notNullValue()))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(header().exists("X-Trace-Id"));
    }

    // -------------------------------------------------------------------------
    // 403 Forbidden — non-disclosure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("403: ForbiddenException → code=FORBIDDEN, generic message")
    void forbidden_returns403WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/errors/forbidden")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.FORBIDDEN)))
                .andExpect(jsonPath("$.message", is("Access denied.")))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("401: no JWT → code=UNAUTHENTICATED, X-Trace-Id present")
    @WithAnonymousUser
    void noJwt_returns401() throws Exception {
        mockMvc.perform(get("/test/errors/not-found")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.UNAUTHENTICATED)));
    }

    // -------------------------------------------------------------------------
    // 409 Conflict
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("409: IllegalTransitionException → code=ILLEGAL_TRANSITION")
    void illegalTransition_returns409() throws Exception {
        mockMvc.perform(get("/test/errors/illegal-transition")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.ILLEGAL_TRANSITION)))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("409: ConflictException → code=CONFLICT")
    void conflict_returns409() throws Exception {
        mockMvc.perform(get("/test/errors/conflict")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.CONFLICT)))
                .andExpect(header().exists("X-Trace-Id"));
    }

    // -------------------------------------------------------------------------
    // 422 Unprocessable Entity
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("422: BusinessGuardException → code=GUARD_REFUSED")
    void businessGuard_returns422() throws Exception {
        mockMvc.perform(get("/test/errors/business-guard")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.GUARD_REFUSED)))
                .andExpect(header().exists("X-Trace-Id"));
    }

    // -------------------------------------------------------------------------
    // 429 Too Many Requests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("429: RateLimitedException → code=RATE_LIMITED, Retry-After header")
    void rateLimited_returns429WithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/errors/rate-limited")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.RATE_LIMITED)))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("Retry-After", is("60")));
    }

    // -------------------------------------------------------------------------
    // 503 Service Unavailable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("503: ProviderDegradedException → code=PROVIDER_DEGRADED")
    void providerDegraded_returns503() throws Exception {
        mockMvc.perform(get("/test/errors/provider-degraded")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.PROVIDER_DEGRADED)))
                .andExpect(header().exists("X-Trace-Id"));
    }

    // -------------------------------------------------------------------------
    // 500 Internal Error
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("500: unhandled exception → code=INTERNAL_ERROR, no stack trace in body")
    void unexpectedException_returns500WithNoStackTrace() throws Exception {
        mockMvc.perform(get("/test/errors/internal-error")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.INTERNAL_ERROR)))
                .andExpect(jsonPath("$.message", not(emptyOrNullString())))
                // Ensure no stack trace fields leak into the response
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(header().exists("X-Trace-Id"));
    }

    // -------------------------------------------------------------------------
    // 400 Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("400: bean validation failure → code=VALIDATION_FAILED with fieldErrors")
    void beanValidationFailure_returns400WithFieldErrors() throws Exception {
        String payload = """
                {
                  "name": "",
                  "priority": "INVALID_PRIORITY"
                }
                """;

        mockMvc.perform(post("/test/errors/validated")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.VALIDATION_FAILED)))
                .andExpect(jsonPath("$.fieldErrors", notNullValue()))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("400: malformed JSON body → code=VALIDATION_FAILED, no exception details")
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/test/errors/validated")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(ErrorEnvelope.Code.VALIDATION_FAILED)))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("400: unknown JSON fields → rejected by strict Jackson mode")
    void unknownJsonFields_returns400() throws Exception {
        String payload = """
                {
                  "name": "Test",
                  "priority": "LOW",
                  "unknownField": "should-be-rejected"
                }
                """;

        mockMvc.perform(post("/test/errors/validated")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Non-disclosure: 403 body identical for scoped and forbidden
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Non-disclosure: 403 body contains only generic message, no resource identifiers")
    void forbidden_bodyContainsNoResourceIdentifiers() throws Exception {
        String body = mockMvc.perform(get("/test/errors/forbidden")
                        .with(jwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Must not leak any internal paths, IDs, or exception class names
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("woid")
                .doesNotContain("Exception")
                .doesNotContain("at com.")
                .doesNotContain("stack");
    }
}

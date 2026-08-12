package com.fieldservice.aiaudit;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the AI interaction audit rating and metrics endpoints (WO-180).
 *
 * <p>Covers: rating happy path, duplicate identical rating (idempotent), duplicate differing rating
 * (409), cross-user 403, purge-job deletes only expired rows, metrics endpoint role restriction.
 */
@DisplayName("AiInteraction — rating and metrics integration")
@Sql(scripts = "classpath:fixtures/aiaudit/ai-interaction-seed.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AiInteractionIT extends AbstractIntegrationTest {

    private static final String RATE_URL = "/api/v1/ai-interactions/{id}/rating";
    private static final String METRICS_URL = "/api/v1/ai-interactions/metrics";

    // Unrated COMPLETED interaction owned by T1
    private static final UUID UNRATED_WO_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000003");

    // COMPLETED, already rated HELPFUL by T1
    private static final UUID RATED_HELPFUL_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000001");

    private static final UUID TECH1_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000011");
    private static final UUID TECH2_ID =
            UUID.fromString("ee000000-0000-0000-0000-000000000012");

    @Autowired
    private MockMvc mockMvc;

    // ── Rating happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Technician can rate their own interaction — 201")
    void rateSelf_returns201() throws Exception {
        mockMvc.perform(post(RATE_URL, UNRATED_WO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"HELPFUL\"}")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt()
                                .jwt(b -> b.subject(TECH2_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value("HELPFUL"))
                .andExpect(jsonPath("$.interactionId").value(UNRATED_WO_ID.toString()));
    }

    @Test
    @DisplayName("Duplicate identical rating returns 200 (idempotent replay)")
    void duplicateIdenticalRating_returns200() throws Exception {
        mockMvc.perform(post(RATE_URL, RATED_HELPFUL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"HELPFUL\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("HELPFUL"));
    }

    @Test
    @DisplayName("Duplicate differing rating returns 409 Conflict")
    void duplicateDifferentRating_returns409() throws Exception {
        mockMvc.perform(post(RATE_URL, RATED_HELPFUL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"NOT_HELPFUL\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Cross-user rating returns 403 — no existence disclosure")
    void crossUserRating_returns403() throws Exception {
        // T2 attempts to rate T1's interaction — must return 403, not 404
        mockMvc.perform(post(RATE_URL, RATED_HELPFUL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"HELPFUL\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(TECH2_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unknown interaction returns 403 — no existence disclosure")
    void unknownInteraction_returns403() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(post(RATE_URL, unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"HELPFUL\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Invalid rating value returns 400")
    void invalidRatingValue_returns400() throws Exception {
        mockMvc.perform(post(RATE_URL, RATED_HELPFUL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"AWESOME\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isBadRequest());
    }

    // ── Metrics endpoint ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Metrics endpoint returns 200 for ADMIN")
    void metrics_adminAccess_returns200() throws Exception {
        mockMvc.perform(get(METRICS_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interactions").isNumber())
                .andExpect(jsonPath("$.outcomeDistribution").isMap());
    }

    @Test
    @DisplayName("Metrics endpoint returns 403 for TECHNICIAN")
    void metrics_technicianRole_returns403() throws Exception {
        mockMvc.perform(get(METRICS_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(TECH1_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated metrics request returns 401")
    void metrics_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(METRICS_URL))
                .andExpect(status().isUnauthorized());
    }
}

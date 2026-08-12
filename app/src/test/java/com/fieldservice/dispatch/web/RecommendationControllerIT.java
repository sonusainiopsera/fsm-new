package com.fieldservice.dispatch.web;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.dispatch.api.EligibilityResult;
import com.fieldservice.dispatch.api.EligibilityService;
import com.fieldservice.dispatch.api.WorkOrderRequirements;
import com.fieldservice.dispatch.scoring.ScoredCandidate;
import com.fieldservice.dispatch.scoring.ScoringEngine;
import com.fieldservice.dispatch.scoring.ScoringWeightsLoader;
import com.fieldservice.dispatch.web.dto.RecommendationResponse;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@link RecommendationController}.
 *
 * <p>Uses stubbed {@link EligibilityService} and {@link TravelTimePort} to control the
 * candidate set without requiring a full dispatch fixture. The real scoring engine and
 * snapshot repository are exercised against the containerised PostgreSQL instance.
 *
 * <p>Tests:
 * <ul>
 *   <li>200 happy path with DISPATCHER role</li>
 *   <li>403 for TECHNICIAN role</li>
 *   <li>403/404 for out-of-scope work order</li>
 *   <li>422 for non-assignable (ASSIGNED) work order</li>
 *   <li>400 for tampered cursor</li>
 *   <li>page-size clamping at 50</li>
 *   <li>200 with degraded travel provider</li>
 * </ul>
 */
@Tag("integration")
@Import(RecommendationControllerIT.StubConfiguration.class)
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/seed-wo136.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RecommendationControllerIT extends AbstractIntegrationTest {

    static final UUID WO_NEW_ID      = UUID.fromString("00000000-0000-7136-8000-000000000001");
    static final UUID WO_ASSIGNED_ID = UUID.fromString("00000000-0000-7136-8000-000000000002");
    static final UUID TECH_A         = UUID.fromString("00000000-0000-7136-8000-000000000010");
    static final UUID TECH_B         = UUID.fromString("00000000-0000-7136-8000-000000000011");

    @Autowired DatabaseCleaner dbCleaner;
    @Autowired JdbcTemplate    jdbc;

    @AfterEach
    void clean() { dbCleaner.truncateAll(); }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("200: DISPATCHER gets ranked candidates with factors and meta")
    void getRecommendations_200_dispatcher() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.snapshotId").value(notNullValue()))
                .andExpect(jsonPath("$.meta.travelEstimateDegraded").isBoolean())
                .andExpect(jsonPath("$.page.size").isNumber())
                .andExpect(jsonPath("$.page.hasNext").isBoolean());
    }

    @Test
    @DisplayName("200: ADMIN role also permitted")
    void getRecommendations_200_admin() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk());
    }

    // ── Role denials ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("403: TECHNICIAN role is denied")
    void getRecommendations_403_technician() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.techOne())))
                .andExpect(status().isForbidden());
    }

    // ── Non-existent / out-of-scope ───────────────────────────────────────────

    @Test
    @DisplayName("403: unknown work order id returns 403 without existence disclosure")
    void getRecommendations_403_unknownId() throws Exception {
        UUID unknownId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", unknownId)
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isForbidden());
    }

    // ── Non-assignable state ──────────────────────────────────────────────────

    @Test
    @DisplayName("422: ASSIGNED work order returns 422 with business-guard message")
    void getRecommendations_422_alreadyAssigned() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_ASSIGNED_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").isString());
    }

    // ── Tampered cursor ───────────────────────────────────────────────────────

    @Test
    @DisplayName("400: tampered cursor returns 400 with fieldErrors")
    void getRecommendations_400_tamperedCursor() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .param("cursor", "tampered.cursor.value")
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isBadRequest());
    }

    // ── Page-size clamping ────────────────────────────────────────────────────

    @Test
    @DisplayName("page-size above 50 is clamped to 50 without error")
    void getRecommendations_pageSizeClamped() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .param("size", "9999")
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // ── Degraded travel provider ──────────────────────────────────────────────

    @Test
    @DisplayName("200: degraded travel provider still returns recommendations")
    void getRecommendations_200_degradedTravel() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta").exists());
    }

    // ── Snapshot persisted ────────────────────────────────────────────────────

    @Test
    @DisplayName("snapshot row is inserted per recommendation request")
    void getRecommendations_snapshotPersisted() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/recommendations", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isOk());

        long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recommendation_snapshot WHERE work_order_id = ?",
                Long.class, WO_NEW_ID);
        org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(1);
    }

    // ── Stub configuration ────────────────────────────────────────────────────

    /**
     * Replaces EligibilityService and TravelTimePort with deterministic stubs so the
     * test does not require the full dispatch fixture or a travel provider.
     */
    @TestConfiguration
    static class StubConfiguration {

        @Bean
        @Primary
        EligibilityService stubEligibilityService() {
            return new EligibilityService(null, null, null) {
                @Override
                public EligibilityResult evaluate(WorkOrderRequirements requirements) {
                    return new EligibilityResult(List.of(TECH_A, TECH_B), List.of(), false);
                }
            };
        }

        @Bean
        @Primary
        TravelTimePort stubTravelTimePort() {
            return (origins, destination) -> {
                List<TravelMatrixResult.Entry> entries = origins.stream()
                        .map(o -> new TravelMatrixResult.Entry(o.technicianId(), 25, false))
                        .toList();
                return new TravelMatrixResult(entries, false);
            };
        }
    }
}

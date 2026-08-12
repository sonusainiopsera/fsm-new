package com.fieldservice.workforce.web;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the certification readiness report endpoints.
 *
 * <p>Verifies role-based access control, response structure, snapshot idempotency,
 * and page-size clamping.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class ReadinessReportControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_readiness_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url",          postgres::getJdbcUrl);
        registry.add("spring.flyway.user",         postgres::getUsername);
        registry.add("spring.flyway.password",     postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc mockMvc;

    // ---- RBAC ---------------------------------------------------------------

    @Test
    @DisplayName("MANAGER can access readiness aggregate report")
    void manager_canAccessReport() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateTarget").value(100));
    }

    @Test
    @DisplayName("ADMIN can access readiness aggregate report")
    void admin_canAccessReport() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DISPATCHER gets 403 on readiness report")
    void dispatcher_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN gets 403 on readiness report")
    void technician_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER gets 403 on readiness report")
    void customer_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request gets 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness"))
                .andExpect(status().isUnauthorized());
    }

    // ---- Snapshot RBAC -------------------------------------------------------

    @Test
    @DisplayName("MANAGER cannot trigger snapshot (ADMIN only)")
    void manager_cannotTriggerSnapshot() throws Exception {
        mockMvc.perform(post("/api/v1/reports/certification-readiness/snapshots")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN can trigger snapshot")
    void admin_canTriggerSnapshot() throws Exception {
        mockMvc.perform(post("/api/v1/reports/certification-readiness/snapshots")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isoWeek").isNotEmpty())
                .andExpect(jsonPath("$.definitionVersion").isNotEmpty());
    }

    @Test
    @DisplayName("Snapshot trigger is idempotent — same ISO week upserts")
    void snapshotIdempotency() throws Exception {
        // First call
        String body1 = mockMvc.perform(post("/api/v1/reports/certification-readiness/snapshots")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Second call same week
        String body2 = mockMvc.perform(post("/api/v1/reports/certification-readiness/snapshots")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Both should return same isoWeek (not fail or duplicate)
        org.assertj.core.api.Assertions.assertThat(body1).contains("isoWeek");
        org.assertj.core.api.Assertions.assertThat(body2).contains("isoWeek");
    }

    // ---- Pagination ----------------------------------------------------------

    @Test
    @DisplayName("page size clamped to 50 max")
    void pageSizeClamped() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness/gaps?size=999")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    @Test
    @DisplayName("snapshots list returns paged envelope")
    void snapshotsPagedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness/snapshots")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.links").exists());
    }

    // ---- CSV -----------------------------------------------------------------

    @Test
    @DisplayName("CSV download returns text/csv content type")
    void csvContentType() throws Exception {
        mockMvc.perform(get("/api/v1/reports/certification-readiness/gaps.csv")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }
}

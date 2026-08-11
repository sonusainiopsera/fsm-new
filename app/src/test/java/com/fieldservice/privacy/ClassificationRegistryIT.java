package com.fieldservice.privacy;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.privacy.api.ClassificationService;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "app.privacy.consistency-check.enabled=false"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class ClassificationRegistryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_privacy_test")
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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired JdbcTemplate      jdbc;
    @Autowired ClassificationService service;
    @Autowired MockMvc            mockMvc;

    // ---- Migration: table exists ------------------------------------------------

    @Test
    @DisplayName("V25 migration creates data_classification table")
    void migration_creates_dataClassification_table() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'data_classification'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("V25 migration creates data_classification_aud table")
    void migration_creates_dataClassificationAud_table() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'data_classification_aud'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    // ---- Seed data assertions ---------------------------------------------------

    @Test
    @DisplayName("V25 seed includes RESTRICTED row for AppUser.passwordHash")
    void seed_restrictedRow_forPasswordHash_exists() {
        Optional<ClassificationView> view = service.findByEntityAndField("AppUser", "passwordHash");
        assertThat(view).isPresent();
        assertThat(view.get().tier()).isEqualTo(ClassificationTier.RESTRICTED);
    }

    @Test
    @DisplayName("V25 seed includes INTERNAL row for WorkOrder")
    void seed_internalRow_forWorkOrder_exists() {
        Optional<ClassificationView> view = service.findByEntity("WorkOrder");
        assertThat(view).isPresent();
        assertThat(view.get().tier()).isEqualTo(ClassificationTier.INTERNAL);
    }

    @Test
    @DisplayName("V25 seed includes PUBLIC row for SlaPolicy")
    void seed_publicRow_forSlaPolicy_exists() {
        Optional<ClassificationView> view = service.findByEntity("SlaPolicy");
        assertThat(view).isPresent();
        assertThat(view.get().tier()).isEqualTo(ClassificationTier.PUBLIC);
    }

    @Test
    @DisplayName("findByTier returns all CONFIDENTIAL rows including CustomerAccount")
    void findByTier_returnsConfidentialRows() {
        List<ClassificationView> rows = service.findByTier(ClassificationTier.CONFIDENTIAL);
        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(ClassificationView::entityName)
                .contains("CustomerAccount");
    }

    // ---- CHECK constraint -------------------------------------------------------

    @Test
    @DisplayName("CHECK constraint rejects invalid tier")
    void checkConstraint_rejects_invalidTier() {
        try {
            jdbc.execute("INSERT INTO data_classification (id, module, entity_name, tier, version) "
                    + "VALUES (gen_random_uuid(), 'test', 'TestEntity', 'TOPSECRET', 0)");
            throw new AssertionError("Should have thrown");
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    // ---- MockMvc: 200 on GET with PRIVACY_ADMIN ---------------------------------

    @Test
    @DisplayName("GET /api/v1/privacy/classifications returns 200 for PRIVACY_ADMIN")
    void get_classifications_returns200_forPrivacyAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/classifications")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PRIVACY_ADMIN")))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.number").value(0));
    }

    // ---- MockMvc: 403 for non-privacy role --------------------------------------

    @Test
    @DisplayName("GET /api/v1/privacy/classifications returns 403 for DISPATCHER")
    void get_classifications_returns403_forDispatcher() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/classifications")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/privacy/classifications returns 403 for TECHNICIAN")
    void get_classifications_returns403_forTechnician() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/classifications")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ---- MockMvc: 404 on PUT unknown id -----------------------------------------

    @Test
    @DisplayName("PUT /api/v1/privacy/classifications/{unknown} returns 404")
    void put_unknownId_returns404() throws Exception {
        String body = "{\"tier\":\"INTERNAL\",\"version\":0}";
        mockMvc.perform(put("/api/v1/privacy/classifications/00000000-0000-0000-0000-000000000000")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ---- MockMvc: 400 on PUT invalid tier ---------------------------------------

    @Test
    @DisplayName("PUT /api/v1/privacy/classifications/{id} returns 400 on invalid tier")
    void put_invalidTier_returns400() throws Exception {
        String body = "{\"tier\":\"TOPSECRET\",\"version\":0}";
        mockMvc.perform(put("/api/v1/privacy/classifications/00000000-0000-7025-8000-000000000020")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- MockMvc: pagination page size clamped to 50 ----------------------------

    @Test
    @DisplayName("GET with size=100 is clamped to 50 rows max")
    void get_sizeAbove50_isClamped() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/classifications?size=100")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }
}

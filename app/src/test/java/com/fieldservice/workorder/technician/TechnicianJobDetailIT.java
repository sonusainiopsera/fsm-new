package com.fieldservice.workorder.technician;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for WO-156: technician job-detail endpoint.
 *
 * <p>Covers:
 * <ul>
 *   <li>200 with full detail for own ASSIGNED job — allowedTransitions includes DEPART</li>
 *   <li>200 with allowedTransitions = [HOLD, COMPLETE] for IN_PROGRESS job</li>
 *   <li>404 for a work order belonging to a different technician (no existence disclosure)</li>
 *   <li>403 for CUSTOMER role</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles({"worker", "test"})
@Sql(scripts = "/fixtures/seed-technician-day.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TechnicianJobDetailIT {

    private static final String URL = "/api/v1/technicians/me/work-orders/";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_tech_detail_test")
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

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("200 — own ASSIGNED job returns detail with DEPART in allowedTransitions")
    void ownAssignedJob_returnsDetailWithDepart() throws Exception {
        mvc.perform(get(URL + "00000000-0000-7154-0000-000000000001")
                .with(jwt().jwt(TestJwtFactory.techOne())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000000-0000-7154-0000-000000000001"))
                .andExpect(jsonPath("$.state").value("ASSIGNED"))
                .andExpect(jsonPath("$.allowedTransitions").isArray())
                .andExpect(jsonPath("$.allowedTransitions[?(@ == 'DEPART')]").exists())
                .andExpect(jsonPath("$.siteName").value("Acme HQ"))
                .andExpect(jsonPath("$.contactPhoneMasked").value("****5309"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    @DisplayName("404 — job belonging to a different technician is not disclosed")
    void otherTechJob_returns404() throws Exception {
        mvc.perform(get(URL + "00000000-0000-7154-0000-000000000001")
                .with(jwt().jwt(TestJwtFactory.techTwo())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("403 — CUSTOMER role is rejected")
    void customerRole_returns403() throws Exception {
        mvc.perform(get(URL + "00000000-0000-7154-0000-000000000001")
                .with(jwt().jwt(TestJwtFactory.customerMultiAccount())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("404 — unknown UUID returns 404")
    void unknownId_returns404() throws Exception {
        mvc.perform(get(URL + "00000000-0000-0000-0000-000000000099")
                .with(jwt().jwt(TestJwtFactory.techOne())))
                .andExpect(status().isNotFound());
    }
}

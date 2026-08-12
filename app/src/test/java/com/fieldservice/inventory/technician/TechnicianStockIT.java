package com.fieldservice.inventory.technician;

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
 * Integration tests for WO-157: GET /api/v1/technicians/me/stock.
 *
 * <p>Covers:
 * <ul>
 *   <li>200 — technician sees only their own vehicle stock lines</li>
 *   <li>200 — TECH_TWO sees empty (no van assigned in fixture)</li>
 *   <li>403 — CUSTOMER role denied</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles({"worker", "test"})
@Sql(scripts = {"/fixtures/seed-technician-day.sql", "/fixtures/seed-wo157.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TechnicianStockIT {

    private static final String URL = "/api/v1/technicians/me/stock";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_tech_stock_test")
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
    @DisplayName("200 — Tech One sees their own vehicle stock lines")
    void techOne_seesOwnStock() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.techOne())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].partCode").exists())
                .andExpect(jsonPath("$.data[0].quantityOnHand").isNumber())
                .andExpect(jsonPath("$.data[0].locationId").exists());
    }

    @Test
    @DisplayName("200 — Tech Two sees empty list (no van in fixture)")
    void techTwo_seesEmpty() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.techTwo())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("403 — CUSTOMER role denied")
    void customer_denied() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.customerMultiAccount())))
                .andExpect(status().isForbidden());
    }
}

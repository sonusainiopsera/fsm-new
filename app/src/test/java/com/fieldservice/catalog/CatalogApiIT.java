package com.fieldservice.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for catalog endpoints.
 * Covers CRUD for customers, sites and assets, envelope shape, page-size clamping,
 * hierarchy guard refusals, and cross-role access restrictions.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class CatalogApiIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_catalog_test")
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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ---- Customer list --------------------------------------------------------

    @Test
    @DisplayName("ADMIN can list customers and receives paginated envelope")
    void admin_canListCustomers() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").value(notNullValue()))
                .andExpect(jsonPath("$.links").value(notNullValue()));
    }

    @Test
    @DisplayName("page size clamped to 50 when 51 requested")
    void pageSize_clampedAt50() throws Exception {
        mockMvc.perform(get("/api/v1/customers?size=51")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(lessThanOrEqualTo(50)));
    }

    // ---- Customer create / deactivate ----------------------------------------

    @Test
    @DisplayName("ADMIN can create and then deactivate a customer")
    void admin_createAndDeactivateCustomer() throws Exception {
        String unique = java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Map<String, Object> body = Map.of(
                "accountCode", "TST-" + unique,
                "legalName",   "Test Corp " + unique
        );

        String location = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getHeader("Location");

        // Extract id from Location header
        String id = location.substring(location.lastIndexOf('/') + 1);

        // Deactivate
        mockMvc.perform(delete("/api/v1/customers/" + id)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isNoContent());

        // Record still retrievable (deactivated, not deleted)
        mockMvc.perform(get("/api/v1/customers/" + id)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ---- Hierarchy guard -------------------------------------------------------

    @Test
    @DisplayName("Creating site under inactive customer returns 422")
    void createSite_underInactiveCustomer_returns422() throws Exception {
        // First create and deactivate a customer
        String unique = java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Map<String, Object> custBody = Map.of("accountCode", "INX-" + unique, "legalName", "Inactive " + unique);

        String loc = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(custBody))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        String customerId = loc.substring(loc.lastIndexOf('/') + 1);

        mockMvc.perform(delete("/api/v1/customers/" + customerId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isNoContent());

        // Attempt to create site under now-inactive customer
        Map<String, Object> siteBody = Map.of("siteCode", "S01", "displayName", "New Site");
        mockMvc.perform(post("/api/v1/customers/" + customerId + "/sites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(siteBody))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- Site create and asset lifecycle ------------------------------------

    @Test
    @DisplayName("Full lifecycle: create customer → site → asset → deactivate asset")
    void fullLifecycle_customerSiteAsset() throws Exception {
        String unique = java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // Create customer
        Map<String, Object> custBody = Map.of("accountCode", "LCY-" + unique, "legalName", "Lifecycle Corp " + unique);
        String custLoc = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(custBody))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        String customerId = custLoc.substring(custLoc.lastIndexOf('/') + 1);

        // Create site under customer
        Map<String, Object> siteBody = Map.of(
                "siteCode", "LCY-S01",
                "displayName", "Lifecycle Site 01"
        );
        String siteLoc = mockMvc.perform(post("/api/v1/customers/" + customerId + "/sites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(siteBody))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        String siteId = siteLoc.substring(siteLoc.lastIndexOf('/') + 1);

        // Create asset under site
        Map<String, Object> assetBody = Map.of(
                "assetTag", "LCY-AST-001",
                "manufacturer", "TestMfr",
                "model", "TestModel"
        );
        String assetLoc = mockMvc.perform(post("/api/v1/sites/" + siteId + "/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assetBody))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getHeader("Location");
        String assetId = assetLoc.substring(assetLoc.lastIndexOf('/') + 1);

        // List assets for site
        mockMvc.perform(get("/api/v1/sites/" + siteId + "/assets")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        // Deactivate asset
        mockMvc.perform(delete("/api/v1/sites/" + siteId + "/assets/" + assetId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isNoContent());
    }

    // ---- CUSTOMER scope access control ----------------------------------------

    @Test
    @DisplayName("CUSTOMER can only see their own customers (scoped to customerAccountIds)")
    void customerRole_scopedToOwnAccounts() throws Exception {
        // A CUSTOMER with no linked account IDs sees empty list
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(t -> t.subject("cust-999")
                                          .claim("roles", List.of("CUSTOMER"))
                                          .claim("customerAccountIds", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ---- Validation -----------------------------------------------------------

    @Test
    @DisplayName("POST with unknown property returns 400")
    void post_unknownProperty_returns400() throws Exception {
        Map<String, Object> body = Map.of(
                "accountCode", "VALID-001",
                "legalName", "Valid Corp",
                "unknownField", "this-should-fail"
        );
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with missing required fields returns 400")
    void post_missingRequiredFields_returns400() throws Exception {
        Map<String, Object> body = Map.of("legalName", "Missing Account Code");
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isBadRequest());
    }
}

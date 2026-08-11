package com.fieldservice.inventory;

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

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for inventory endpoints.
 * Verifies pagination envelope shape, size clamping, and role-based access control.
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
class InventoryApiIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_inv_api_test")
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

    @Autowired
    MockMvc mockMvc;

    // ---- Parts endpoint --------------------------------------------------------

    @Test
    @DisplayName("DISPATCHER can list parts and receives paginated envelope")
    void dispatcher_can_list_parts() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-1").claim("roles", List.of("DISPATCHER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").value(notNullValue()))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.links").value(notNullValue()));
    }

    @Test
    @DisplayName("Size is clamped to 50 even if client requests more")
    void size_is_clamped_to_50() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts?size=999")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-1").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(lessThanOrEqualTo(50)))
                .andExpect(jsonPath("$.data", hasSize(lessThanOrEqualTo(50))));
    }

    @Test
    @DisplayName("TECHNICIAN can list parts — full catalogue visible")
    void technician_can_list_parts() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(t -> t.subject("tech-1")
                                        .claim("technician_id", "ffffffff-0000-7005-8000-000000000001")
                                        .claim("roles", List.of("TECHNICIAN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("CUSTOMER is denied parts endpoint with 403")
    void customer_is_denied_parts() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(t -> t.subject("cust-1").claim("roles", List.of("CUSTOMER")))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unknown sort field returns 400")
    void unknown_sort_field_returns_400() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts?sort=unknown:asc")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-1").claim("roles", List.of("DISPATCHER")))))
                .andExpect(status().isBadRequest());
    }

    // ---- Stock endpoint --------------------------------------------------------

    @Test
    @DisplayName("DISPATCHER can list stock balances and receives paginated envelope")
    void dispatcher_can_list_stock() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-1").claim("roles", List.of("DISPATCHER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").value(notNullValue()));
    }

    @Test
    @DisplayName("CUSTOMER is denied stock endpoint with 403")
    void customer_is_denied_stock() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(t -> t.subject("cust-1").claim("roles", List.of("CUSTOMER")))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN without technician_id sees empty stock list (denied by scope)")
    void technician_without_tech_id_sees_empty_stock() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(t -> t.subject("no-id-tech").claim("roles", List.of("TECHNICIAN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}

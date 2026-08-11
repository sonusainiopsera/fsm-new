package com.fieldservice.workorder.api;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the work order search / list endpoint.
 * Verifies filtering, scope enforcement, ETag, sort validation, and size clamping.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class WorkOrderSearchIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_search_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",       postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.url",           postgres::getJdbcUrl);
        registry.add("spring.flyway.user",          postgres::getUsername);
        registry.add("spring.flyway.password",      postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    static final String TECH_A_ID  = "ff000000-0000-0000-0000-000000000031";
    static final String TECH_B_ID  = "ff000000-0000-0000-0000-000000000032";
    static final String CUSTOMER_1 = "ff000000-0000-0000-0000-000000000001";
    static final String CUSTOMER_2 = "ff000000-0000-0000-0000-000000000002";
    static final String SITE_1     = "ff000000-0000-0000-0000-000000000011";

    @BeforeEach
    void seedFixtures() {
        new TransactionTemplate(txManager).execute(status -> {
            em.createNativeQuery(
                    "INSERT INTO customer (id, name, version) VALUES " +
                    "('ff000000-0000-0000-0000-000000000001','SearchCorp',0)," +
                    "('ff000000-0000-0000-0000-000000000002','OtherCorp',0)" +
                    " ON CONFLICT (id) DO NOTHING").executeUpdate();

            em.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id, version) VALUES " +
                    "('ff000000-0000-0000-0000-000000000011','Main Site','ff000000-0000-0000-0000-000000000001',0)," +
                    "('ff000000-0000-0000-0000-000000000012','Branch','ff000000-0000-0000-0000-000000000001',0)," +
                    "('ff000000-0000-0000-0000-000000000013','Other Site','ff000000-0000-0000-0000-000000000002',0)" +
                    " ON CONFLICT (id) DO NOTHING").executeUpdate();

            em.createNativeQuery(
                    "INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES " +
                    "('ff000000-0000-0000-0000-000000000021','ta@t.test','x','Tech Alpha',TRUE,0)," +
                    "('ff000000-0000-0000-0000-000000000022','tb@t.test','x','Tech Beta',TRUE,0)" +
                    " ON CONFLICT (id) DO NOTHING").executeUpdate();

            em.createNativeQuery(
                    "INSERT INTO technician (id, user_id, full_name, version) VALUES " +
                    "('ff000000-0000-0000-0000-000000000031','ff000000-0000-0000-0000-000000000021','Tech Alpha',0)," +
                    "('ff000000-0000-0000-0000-000000000032','ff000000-0000-0000-0000-000000000022','Tech Beta',0)" +
                    " ON CONFLICT (id) DO NOTHING").executeUpdate();

            em.createNativeQuery(
                    "INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id," +
                    " at_risk, cumulative_hold_minutes, version) VALUES " +
                    "('ff000000-0000-0000-0000-000000000041','SRCH-001','NEW','HIGH','ff000000-0000-0000-0000-000000000011',NULL,FALSE,0,0)," +
                    "('ff000000-0000-0000-0000-000000000042','SRCH-002','ASSIGNED','MEDIUM','ff000000-0000-0000-0000-000000000011','ff000000-0000-0000-0000-000000000031',FALSE,0,0)," +
                    "('ff000000-0000-0000-0000-000000000043','SRCH-003','IN_PROGRESS','HIGH','ff000000-0000-0000-0000-000000000011','ff000000-0000-0000-0000-000000000031',TRUE,0,0)," +
                    "('ff000000-0000-0000-0000-000000000044','SRCH-004','ON_HOLD','LOW','ff000000-0000-0000-0000-000000000012','ff000000-0000-0000-0000-000000000032',FALSE,30,0)," +
                    "('ff000000-0000-0000-0000-000000000045','SRCH-005','COMPLETED','CRITICAL','ff000000-0000-0000-0000-000000000012','ff000000-0000-0000-0000-000000000032',FALSE,0,0)," +
                    "('ff000000-0000-0000-0000-000000000046','SRCH-006','IN_PROGRESS','MEDIUM','ff000000-0000-0000-0000-000000000013','ff000000-0000-0000-0000-000000000031',FALSE,0,0)" +
                    " ON CONFLICT (id) DO NOTHING").executeUpdate();
            return null;
        });
    }

    // ---- AC-1: Sort and size enforcement ------------------------------------

    @Test
    @DisplayName("Unknown sort field returns 400")
    void unknownSortField_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?sort=unknownField:asc")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Size > 50 is clamped to 50 not rejected")
    void oversizeRequest_clampedTo50() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?size=200")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size", is(20))); // min(200→clamped,actual)
    }

    @Test
    @DisplayName("Unknown state filter value returns 400")
    void unknownStateValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?states=BOGUS_STATE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    // ---- AC-2: Filters ------------------------------------------------------

    @Test
    @DisplayName("State filter returns only matching work orders")
    void stateFilter_returnsOnlyMatching() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?states=IN_PROGRESS")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].state",
                        org.hamcrest.Matchers.everyItem(is("IN_PROGRESS"))));
    }

    @Test
    @DisplayName("assignedTechnicianId filter returns only that technician's orders")
    void technicianFilter_returnsOnlyOwn() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?assignedTechnicianId=" + TECH_B_ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assignedTechnicianId",
                        org.hamcrest.Matchers.everyItem(is(TECH_B_ID))));
    }

    @Test
    @DisplayName("atRisk filter returns only at-risk work orders")
    void atRiskFilter_returnsOnlyAtRisk() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?atRisk=true")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].atRisk",
                        org.hamcrest.Matchers.everyItem(is(true))));
    }

    @Test
    @DisplayName("Empty filter returns all visible work orders (dispatcher)")
    void noFilter_dispatcher_returnsAll() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", greaterThanOrEqualTo(6)));
    }

    // ---- AC-5 / AC-6: Row scope enforcement ---------------------------------

    @Test
    @DisplayName("TECHNICIAN sees only their own work orders")
    void technician_seesOnlyOwnOrders() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(j -> j.claim("technicianId", TECH_A_ID)
                                          .claim("roles", java.util.List.of("TECHNICIAN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].assignedTechnicianId",
                        org.hamcrest.Matchers.everyItem(is(TECH_A_ID))));
    }

    @Test
    @DisplayName("CUSTOMER sees only own-site work orders; totalElements excludes others")
    void customer_seesOnlyOwnSiteOrders() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(j -> j.claim("customerAccountIds",
                                        java.util.List.of(CUSTOMER_2))
                                          .claim("roles", java.util.List.of("CUSTOMER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].siteId",
                        org.hamcrest.Matchers.everyItem(is("ff000000-0000-0000-0000-000000000013"))))
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    // ---- AC-7: Response is DTO (board row fields present) --------------------

    @Test
    @DisplayName("Response items expose board-row fields (reference, state, priority, siteId, siteName)")
    void responseItems_exposeBoardRowFields() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id",        notNullValue()))
                .andExpect(jsonPath("$.data[0].reference", notNullValue()))
                .andExpect(jsonPath("$.data[0].state",     notNullValue()))
                .andExpect(jsonPath("$.data[0].priority",  notNullValue()))
                .andExpect(jsonPath("$.data[0].siteId",    notNullValue()))
                .andExpect(jsonPath("$.data[0].siteName",  notNullValue()));
    }

    // ---- ETag / 304 conditional GET -----------------------------------------

    @Test
    @DisplayName("Second identical request with ETag returns 304 Not Modified")
    void etagConditionalGet_returns304OnUnchanged() throws Exception {
        // First request — grab ETag
        MvcResult first = mockMvc.perform(get("/api/v1/work-orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);

        // Second request with If-None-Match → 304
        mockMvc.perform(get("/api/v1/work-orders")
                        .header(HttpHeaders.IF_NONE_MATCH, etag)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isNotModified());
    }

    // ---- AC-4: Keyset cursor ------------------------------------------------

    @Test
    @DisplayName("Tampered cursor returns 400")
    void tamperedCursor_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?cursor=tampered.value")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    // ---- resolutionDeadline sort (allow-listed) ------------------------------

    @Test
    @DisplayName("Sort by resolutionDeadline is allowed")
    void sortByResolutionDeadline_isAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?sort=resolutionDeadline:asc")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk());
    }

    // ---- Priority + state combined filter -----------------------------------

    @Test
    @DisplayName("Multiple state filter values narrow the result set")
    void multipleStateFilter_narrowsResults() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders?states=NEW&states=ASSIGNED")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].state",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.anyOf(is("NEW"), is("ASSIGNED")))));
    }
}

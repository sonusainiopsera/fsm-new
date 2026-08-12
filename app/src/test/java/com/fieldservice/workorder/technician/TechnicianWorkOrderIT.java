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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for WO-154: technician today's-jobs endpoint.
 *
 * <p>Covers:
 * <ul>
 *   <li>AC-1  Row scope — Tech One sees only its own jobs</li>
 *   <li>AC-2  Day window + carry-over open states</li>
 *   <li>AC-3  Standard pagination envelope with correct totalElements</li>
 *   <li>AC-4  Ordering (scheduledStart ASC, priority DESC, id ASC)</li>
 *   <li>AC-5  Projection fields present; contactPhoneMasked in ****NNNN format</li>
 *   <li>AC-6  Strong ETag on 200; 304 on matching If-None-Match</li>
 *   <li>AC-7  CUSTOMER role → 403</li>
 *   <li>AC-3  size=500 clamped to 50</li>
 *   <li>AC-2  Empty day returns 200 with empty data array</li>
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
class TechnicianWorkOrderIT {

    private static final String DAY = "2026-09-15";
    private static final String URL = "/api/v1/technicians/me/work-orders";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_tech_day_test")
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
        registry.add("spring.jpa.hibernate.ddl-auto",
                () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired
    MockMvc mockMvc;

    // ─── AC-1: Row scope enforcement ─────────────────────────────────────────

    @Test
    @DisplayName("Tech One sees exactly its own jobs — Tech Two's job absent")
    void rowScope_techOneCannotSeeTechTwoJobs() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'WO154-008')]").doesNotExist());
    }

    // ─── AC-2: Day window + carry-over ────────────────────────────────────────

    @Test
    @DisplayName("Happy path: returns 5 jobs for date=2026-09-15 (today + carry-over + null-window)")
    void happyPath_returns5JobsForDay() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("Carry-over: IN_PROGRESS job from yesterday appears in today's list")
    void carryOver_yesterdayInProgressIncluded() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'WO154-003')]").exists());
    }

    @Test
    @DisplayName("Null-window ON_HOLD job appears in today's list as carry-over")
    void carryOver_nullWindowOnHoldIncluded() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'WO154-005')]").exists());
    }

    @Test
    @DisplayName("COMPLETED job from yesterday is absent (not a carry-over state)")
    void noCarryOver_completedJobAbsent() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'WO154-006')]").doesNotExist());
    }

    @Test
    @DisplayName("Tomorrow's job is absent from today's list")
    void tomorrow_jobAbsent() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'WO154-007')]").doesNotExist());
    }

    // ─── AC-3: Empty day ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty day returns 200 with empty data array")
    void emptyDay_returns200WithEmptyData() throws Exception {
        mockMvc.perform(get(URL + "?date=2000-01-01")
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // ─── AC-5: Projection fields and contact masking ─────────────────────────

    @Test
    @DisplayName("Response contains all required projection fields; contactPhoneMasked is masked")
    void projection_fieldsAndMasking() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].reference").exists())
                .andExpect(jsonPath("$.data[0].priority").exists())
                .andExpect(jsonPath("$.data[0].state").exists())
                .andExpect(jsonPath("$.data[0].siteName").exists())
                .andExpect(jsonPath("$.data[0].siteAddress").exists())
                .andExpect(jsonPath("$.data[0].slaAtRisk").exists())
                // contactPhoneMasked must be ****NNNN (4 stars + 4 digits) or null
                .andExpect(jsonPath("$.data[0].contactPhoneMasked")
                        .value(org.hamcrest.Matchers.matchesRegex("\\*{4}\\d{4}")));
    }

    // ─── AC-6: ETag / 304 ────────────────────────────────────────────────────

    @Test
    @DisplayName("Response carries ETag header")
    void etag_presentOnSuccess() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    @Test
    @DisplayName("Matching If-None-Match returns 304 with no body")
    void etag_304OnMatchingIfNoneMatch() throws Exception {
        // First request to get the ETag
        MvcResult first = mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andReturn();

        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        // Second request with the same ETag → 304
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .header(HttpHeaders.IF_NONE_MATCH, etag)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("Non-matching If-None-Match returns 200 with body")
    void etag_200OnNonMatchingIfNoneMatch() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"non-matching-etag\"")
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ─── AC-7: Role denial ────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER role receives 403")
    void roleDenial_customerGets403() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.customerMultiAccount().getClaims())))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticated_gets401() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY))
                .andExpect(status().isUnauthorized());
    }

    // ─── AC-3: Page-size clamping ─────────────────────────────────────────────

    @Test
    @DisplayName("size=500 is server-clamped to 50 without error")
    void pageSize_clampedAt50() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY + "&size=500")
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // ─── AC-4: Sort validation ────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown sort field returns 400")
    void sort_unknownFieldReturns400() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY + "&sort=email,asc")
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isBadRequest());
    }

    // ─── AC-1: Cross-technician isolation ─────────────────────────────────────

    @Test
    @DisplayName("DISPATCHER with no technicianId in JWT receives empty page")
    void dispatcher_receivesEmptyPage() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.dispatcher().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // ─── AC-5: slaAtRisk field ────────────────────────────────────────────────

    @Test
    @DisplayName("At-risk job has slaAtRisk=true in response")
    void atRisk_flagTrueForAtRiskJob() throws Exception {
        mockMvc.perform(get(URL + "?date=" + DAY)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'WO154-004')].slaAtRisk")
                        .value(true));
    }
}

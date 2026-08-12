package com.fieldservice.analytics.web;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@link DashboardWidgetController}.
 *
 * <p>Covers:
 * <ul>
 *   <li>200 with ETag, Cache-Control, and Vary headers (MANAGER role)</li>
 *   <li>200 ADMIN role permitted</li>
 *   <li>304 on If-None-Match matching the current ETag</li>
 *   <li>New ETag after underlying data changes (version bump)</li>
 *   <li>400 for invalid metric enum value</li>
 *   <li>400 for invalid window enum value</li>
 *   <li>400 for missing required params</li>
 *   <li>401 for unauthenticated request</li>
 *   <li>403 for DISPATCHER role</li>
 *   <li>403 for TECHNICIAN role</li>
 *   <li>403 for CUSTOMER role</li>
 *   <li>200 degraded payload carries degraded=true and degradedReason</li>
 *   <li>Deterministic ETag across repeated calls</li>
 * </ul>
 */
@Tag("integration")
@Sql(
    scripts = "classpath:fixtures/seed-analytics-widgets.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class DashboardWidgetControllerIT extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/api/v1/analytics/dashboard/widgets";

    @Autowired
    DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
    }

    // ── Happy-path 200 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("200: MANAGER gets widget list with ETag, Cache-Control, and Vary")
    void getWidgets_200_manager() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=30")))
                .andExpect(header().string(HttpHeaders.VARY, containsString(HttpHeaders.AUTHORIZATION)))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].metricKey", is("sla.compliance.rate")))
                .andExpect(jsonPath("$.data[0].window", is("THIRTY_DAYS")))
                .andExpect(jsonPath("$.data[0].value", notNullValue()))
                .andExpect(jsonPath("$.data[0].degraded", is(false)))
                .andExpect(jsonPath("$.data[0].degradedReason", nullValue()))
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    @Test
    @DisplayName("200: ADMIN role also permitted")
    void getWidgets_200_admin() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "BACKLOG_OPEN_COUNT")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].metricKey", is("backlog.open.count")));
    }

    @Test
    @DisplayName("200: multiple metrics returned in request order")
    void getWidgets_200_multipleMetrics() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE", "BACKLOG_OPEN_COUNT", "UTILIZATION_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.totalElements", is(3)))
                .andExpect(jsonPath("$.data[0].metricKey", is("sla.compliance.rate")))
                .andExpect(jsonPath("$.data[1].metricKey", is("backlog.open.count")))
                .andExpect(jsonPath("$.data[2].metricKey", is("workforce.utilization.rate")));
    }

    // ── Conditional GET 304 ───────────────────────────────────────────────────

    @Test
    @DisplayName("304: If-None-Match matching current ETag returns empty body")
    void getWidgets_304_ifNoneMatch() throws Exception {
        // First request — capture ETag
        String etag = mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        // Conditional GET with same ETag
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .header(HttpHeaders.IF_NONE_MATCH, etag)
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("200: ETag changes after underlying projection version is bumped")
    void getWidgets_200_changedEtagAfterMutation() throws Exception {
        // First request — capture ETag
        String originalEtag = mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        // Simulate a version bump (background recomputation)
        tx.execute(status -> {
            entityManager.createNativeQuery(
                    "UPDATE kpi_projection SET projection_version = projection_version + 1 " +
                    "WHERE metric_key = 'sla.compliance.rate' AND segment_key = 'ALL' AND window_key = 'P30D'")
                    .executeUpdate();
            return null;
        });
        entityManager.clear();

        // Second request — ETag must differ
        String newEtag = mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        assert originalEtag != null && !originalEtag.equals(newEtag)
                : "ETag must change after projection_version is incremented";
    }

    @Test
    @DisplayName("200: ETag is deterministic across repeated calls with identical data")
    void getWidgets_200_deterministicEtag() throws Exception {
        String etag1 = mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        String etag2 = mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        assert etag1 != null && etag1.equals(etag2)
                : "ETag must be deterministic for identical projection state";
    }

    // ── Degraded widget ───────────────────────────────────────────────────────

    @Test
    @DisplayName("200: degraded metric returns widget with degraded=true and degradedReason")
    void getWidgets_200_degradedPayload() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_BREACH_COUNT")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].degraded", is(true)))
                .andExpect(jsonPath("$.data[0].degradedReason", notNullValue()))
                .andExpect(jsonPath("$.data[0].metricKey", is("sla.breach.count")));
    }

    @Test
    @DisplayName("200: metric with no projection returns no-data widget (degraded=true, NO_DATA reason)")
    void getWidgets_200_noDataWidget() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "JOBS_PER_DAY")   // not in fixture
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].degraded", is(true)))
                .andExpect(jsonPath("$.data[0].degradedReason", is("NO_DATA")))
                .andExpect(jsonPath("$.data[0].sampleCount", is(0)));
    }

    // ── Validation errors 400 ─────────────────────────────────────────────────

    @Test
    @DisplayName("400: unknown metric enum value")
    void getWidgets_400_invalidMetric() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "MADE_UP_METRIC")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400: unknown window enum value")
    void getWidgets_400_invalidWindow() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "NEXT_YEAR")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400: missing required metrics param")
    void getWidgets_400_missingMetrics() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400: missing required window param")
    void getWidgets_400_missingWindow() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isBadRequest());
    }

    // ── Auth / RBAC ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("401: unauthenticated request returns 401")
    void getWidgets_401_unauthenticated() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("403: DISPATCHER role is denied")
    void getWidgets_403_dispatcher() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("403: TECHNICIAN role is denied")
    void getWidgets_403_technician() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.techOne())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("403: CUSTOMER role is denied")
    void getWidgets_403_customer() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(TestJwtFactory.customerSingleAccount())))
                .andExpect(status().isForbidden());
    }
}

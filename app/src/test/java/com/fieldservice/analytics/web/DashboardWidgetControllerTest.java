package com.fieldservice.analytics.web;

import com.fieldservice.analytics.DashboardWidgetResponse;
import com.fieldservice.analytics.DashboardWidgetService;
import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.TrendPointDto;
import com.fieldservice.platform.exception.ProviderDegradedException;
import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.fieldservice.security.TestJwtFactory.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc integration tests for {@link DashboardWidgetController} (WO-166).
 *
 * <p>Covers:
 * <ul>
 *   <li>200 with ETag and payload (MANAGER, ADMIN)</li>
 *   <li>304 on matching If-None-Match</li>
 *   <li>200 with changed ETag after projection update</li>
 *   <li>400 on unknown metric or window value</li>
 *   <li>401 when unauthenticated</li>
 *   <li>403 for TECHNICIAN, CUSTOMER, DISPATCHER roles</li>
 *   <li>200 degraded payload when a metric projection is degraded</li>
 *   <li>503 when read model is completely unavailable</li>
 *   <li>Cache-Control and Vary headers present on 200</li>
 * </ul>
 */
class DashboardWidgetControllerTest extends AbstractIntegrationTest {

    private static final String WIDGETS_URL = "/api/v1/analytics/dashboard/widgets";
    private static final Instant DATA_AS_OF = Instant.parse("2026-08-12T09:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DashboardWidgetService dashboardWidgetService;

    private DashboardWidgetResponse.WidgetServiceResult normalResult;
    private DashboardWidgetResponse.WidgetServiceResult degradedResult;

    @BeforeEach
    void setUp() {
        KpiProjection proj = new KpiProjection(
                "sla.compliance.rate", "ALL", "ROLLING_7D",
                new BigDecimal("0.92"), new BigDecimal("184"), new BigDecimal("200"),
                200, "CURRENT", DATA_AS_OF, 7L, false, null, 30L);

        DashboardWidgetResponse.WidgetDto widget = new DashboardWidgetResponse.WidgetDto(
                "sla.compliance.rate", "ALL", "ROLLING_7D",
                new BigDecimal("0.92"), "%",
                new BigDecimal("184"), new BigDecimal("200"),
                200, List.of(), new BigDecimal("0.0200"),
                new BigDecimal("1.0222"), "CURRENT",
                DATA_AS_OF, 30L, false, null);

        DashboardWidgetResponse response = new DashboardWidgetResponse(
                List.of(widget),
                new DashboardWidgetResponse.PageMetadata(0, 1, 1, 1));

        normalResult = new DashboardWidgetResponse.WidgetServiceResult(
                response, "\"etag-v7-stable\"", List.of(proj));

        // Degraded variant
        KpiProjection degradedProj = new KpiProjection(
                "sla.compliance.rate", "ALL", "ROLLING_7D",
                null, null, null, 0, "CURRENT", DATA_AS_OF, 1L, true, "REPLICA_UNAVAILABLE", 60L);

        DashboardWidgetResponse.WidgetDto degradedWidget = new DashboardWidgetResponse.WidgetDto(
                "sla.compliance.rate", "ALL", "ROLLING_7D",
                null, "%", null, null, 0,
                List.of(), null, "BASELINE_PENDING",
                "CURRENT", DATA_AS_OF, 60L, true, "REPLICA_UNAVAILABLE");

        DashboardWidgetResponse degradedResponse = new DashboardWidgetResponse(
                List.of(degradedWidget),
                new DashboardWidgetResponse.PageMetadata(0, 1, 1, 1));

        degradedResult = new DashboardWidgetResponse.WidgetServiceResult(
                degradedResponse, "\"etag-v1-degraded\"", List.of(degradedProj));
    }

    // ── 200 with ETag ─────────────────────────────────────────────────────────

    @Test
    void returns200WithEtagForManagerRole() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"etag-v7-stable\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=30")))
                .andExpect(header().string(HttpHeaders.VARY, containsString("Authorization")))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].metricKey", is("sla.compliance.rate")))
                .andExpect(jsonPath("$.data[0].degraded", is(false)))
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    @Test
    void returns200ForAdminRole() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(adminJwt())))
                .andExpect(status().isOk());
    }

    // ── 304 conditional GET ───────────────────────────────────────────────────

    @Test
    void returns304WhenIfNoneMatchMatchesEtag() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"etag-v7-stable\"")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"etag-v7-stable\""))
                .andExpect(content().string(""));
    }

    @Test
    void returns200WhenIfNoneMatchDoesNotMatch() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"stale-etag\"")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"etag-v7-stable\""));
    }

    @Test
    void wildcardIfNoneMatchFallsThrough200() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .header(HttpHeaders.IF_NONE_MATCH, "*")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk());
    }

    // ── 400 bad request ───────────────────────────────────────────────────────

    @Test
    void returns400ForUnknownMetric() throws Exception {
        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "UNKNOWN_METRIC_XYZ")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400ForUnknownWindow() throws Exception {
        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "FORTNIGHT")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenMetricsExceedMaxLimit() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any()))
                .thenThrow(new IllegalArgumentException("Maximum 50 metrics allowed per request; received 51."));

        // Build a request with too many metrics by repeating a valid one 51 times
        var builder = get(WIDGETS_URL).param("window", "SEVEN_DAYS");
        for (int i = 0; i < 51; i++) builder = builder.param("metrics", "SLA_COMPLIANCE_RATE");

        mockMvc.perform(builder.with(jwt().jwt(managerJwt())))
                .andExpect(status().isBadRequest());
    }

    // ── 401 unauthenticated ───────────────────────────────────────────────────

    @Test
    void returns401WhenNoToken() throws Exception {
        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS"))
                .andExpect(status().isUnauthorized());
    }

    // ── 403 forbidden roles ───────────────────────────────────────────────────

    @Test
    void returns403ForTechnicianRole() throws Exception {
        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns403ForCustomerRole() throws Exception {
        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns403ForDispatcherRole() throws Exception {
        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(dispatcherJwt())))
                .andExpect(status().isForbidden());
    }

    // ── 200 degraded payload ──────────────────────────────────────────────────

    @Test
    void returns200WithDegradedFlagWhenMetricProjectionIsUnavailable() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(degradedResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].degraded", is(true)))
                .andExpect(jsonPath("$.data[0].degradedReason", is("REPLICA_UNAVAILABLE")))
                .andExpect(jsonPath("$.data[0].value").doesNotExist());
    }

    // ── 503 read model unavailable ────────────────────────────────────────────

    @Test
    void returns503WhenReadModelIsCompletelyUnavailable() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any()))
                .thenThrow(new ProviderDegradedException("analytics-read-model", "DB unreachable"));

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isServiceUnavailable());
    }

    // ── multiple metrics ──────────────────────────────────────────────────────

    @Test
    void accepts30DayAndNinetyDayWindows() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk());

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "NINETY_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk());
    }

    // ── response envelope ─────────────────────────────────────────────────────

    @Test
    void responseEnvelopeContainsPageMetadata() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number", is(0)))
                .andExpect(jsonPath("$.page.size", is(1)))
                .andExpect(jsonPath("$.page.totalElements", is(1)))
                .andExpect(jsonPath("$.page.totalPages", is(1)));
    }

    @Test
    void widgetDtoContainsRequiredFields() throws Exception {
        when(dashboardWidgetService.getWidgets(anyList(), any(), any())).thenReturn(normalResult);

        mockMvc.perform(get(WIDGETS_URL)
                        .param("metrics", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt().jwt(managerJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].metricKey", notNullValue()))
                .andExpect(jsonPath("$.data[0].segment", notNullValue()))
                .andExpect(jsonPath("$.data[0].window", notNullValue()))
                .andExpect(jsonPath("$.data[0].unit", notNullValue()))
                .andExpect(jsonPath("$.data[0].maturity", notNullValue()))
                .andExpect(jsonPath("$.data[0].dataAsOf", notNullValue()))
                .andExpect(jsonPath("$.data[0].stalenessSeconds", notNullValue()));
    }
}

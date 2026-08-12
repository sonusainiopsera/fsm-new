package com.fieldservice.analytics.drilldown;

import com.fieldservice.analytics.internal.drilldown.MetricFilterTranslator;
import com.fieldservice.analytics.web.WidgetMetricKey;
import com.fieldservice.analytics.web.WidgetWindow;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for MetricFilterTranslator — no Spring context required.
 */
class MetricFilterTranslatorTest {

    private static final Instant NOW = Instant.parse("2025-06-01T12:00:00Z");

    private MetricFilterTranslator translator;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        translator = new MetricFilterTranslator(fixed);
    }

    // ── Window translation ────────────────────────────────────────────────────

    @Test
    void sevenDayWindow_setsCorrectDateRange() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.SEVEN_DAYS, null);

        assertThat(criteria.createdFrom()).isEqualTo(NOW.minusSeconds(7 * 86400));
        assertThat(criteria.createdTo()).isEqualTo(NOW);
    }

    @Test
    void thirtyDayWindow_setsCorrectDateRange() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, null);

        assertThat(criteria.createdFrom()).isEqualTo(NOW.minusSeconds(30 * 86400));
    }

    @Test
    void ninetyDayWindow_setsCorrectDateRange() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.NINETY_DAYS, null);

        assertThat(criteria.createdFrom()).isEqualTo(NOW.minusSeconds(90 * 86400));
    }

    // ── Closed/completed metrics ──────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = WidgetMetricKey.class, names = {
            "SLA_COMPLIANCE_RATE",
            "SLA_RESOLUTION_MEAN",
            "SLA_RESOLUTION_MEDIAN",
            "FIRST_TIME_FIX_RATE",
            "FIRST_TIME_FIX_PROVISIONAL",
            "REPEAT_VISIT_COUNT",
            "JOBS_PER_DAY",
            "UTILIZATION_RATE",
            "WORKLOAD_BALANCE"
    })
    void closedMetrics_includeClosedAndCompleted(WidgetMetricKey metric) {
        WorkOrderSearchCriteria criteria = translator.translate(metric, WidgetWindow.THIRTY_DAYS, null);

        assertThat(criteria.states())
                .containsExactlyInAnyOrder(WorkOrderStatus.CLOSED, WorkOrderStatus.COMPLETED);
        assertThat(criteria.atRisk()).isNull();
    }

    // ── SLA breach: at-risk flag ──────────────────────────────────────────────

    @Test
    void slaBreachCount_setsAtRiskTrue() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.SLA_BREACH_COUNT, WidgetWindow.THIRTY_DAYS, null);

        assertThat(criteria.states())
                .containsExactlyInAnyOrder(WorkOrderStatus.CLOSED, WorkOrderStatus.COMPLETED);
        assertThat(criteria.atRisk()).isTrue();
    }

    // ── Backlog metrics ───────────────────────────────────────────────────────

    @Test
    void backlogOpenCount_includesOpenStates() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, null);

        assertThat(criteria.states()).containsExactlyInAnyOrder(
                WorkOrderStatus.NEW,
                WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.EN_ROUTE,
                WorkOrderStatus.IN_PROGRESS
        );
        assertThat(criteria.atRisk()).isNull();
    }

    @Test
    void backlogOnHoldCount_includesOnlyOnHold() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_ON_HOLD_COUNT, WidgetWindow.THIRTY_DAYS, null);

        assertThat(criteria.states()).containsExactly(WorkOrderStatus.ON_HOLD);
    }

    // ── Segment: no segment ───────────────────────────────────────────────────

    @Test
    void nullSegment_producesNoPriorityFilter() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, null);

        assertThat(criteria.priority()).isNull();
    }

    @Test
    void allSegment_producesNoPriorityFilter() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, "ALL");

        assertThat(criteria.priority()).isNull();
    }

    @Test
    void blankSegment_producesNoPriorityFilter() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, "   ");

        assertThat(criteria.priority()).isNull();
    }

    // ── Segment: PRIORITY:<level> ─────────────────────────────────────────────

    @Test
    void prioritySegment_extractsPriorityUpperCase() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, "PRIORITY:HIGH");

        assertThat(criteria.priority()).isEqualTo("HIGH");
    }

    @Test
    void prioritySegment_lowercaseInput_normalised() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, "PRIORITY:critical");

        assertThat(criteria.priority()).isEqualTo("CRITICAL");
    }

    @Test
    void prioritySegment_emptyLevel_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                translator.translate(WidgetMetricKey.BACKLOG_OPEN_COUNT, WidgetWindow.THIRTY_DAYS, "PRIORITY:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIORITY:");
    }

    // ── extractPriority static helper ─────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "all", "All"})
    void extractPriority_allVariants_returnsNull(String segment) {
        assertThat(MetricFilterTranslator.extractPriority(segment)).isNull();
    }

    @Test
    void extractPriority_priorityHigh_returnsHigh() {
        assertThat(MetricFilterTranslator.extractPriority("PRIORITY:HIGH")).isEqualTo("HIGH");
    }

    @Test
    void extractPriority_unknownSegment_returnsNull() {
        // Future segment types (e.g. TEAM:NORTH) should not fail — they pass as no-filter
        assertThat(MetricFilterTranslator.extractPriority("TEAM:NORTH")).isNull();
    }

    // ── Non-priority criteria are always null from drill-down ─────────────────

    @Test
    void translate_doesNotSetTechnicianOrCustomerFilter() {
        WorkOrderSearchCriteria criteria = translator.translate(
                WidgetMetricKey.UTILIZATION_RATE, WidgetWindow.NINETY_DAYS, null);

        assertThat(criteria.assignedTechnicianId()).isNull();
        assertThat(criteria.customerId()).isNull();
        assertThat(criteria.siteId()).isNull();
    }

    // ── All metric keys translate without exception ───────────────────────────

    @ParameterizedTest
    @EnumSource(WidgetMetricKey.class)
    void allMetricKeys_translateWithoutException(WidgetMetricKey metric) {
        assertThatCode(() ->
                translator.translate(metric, WidgetWindow.THIRTY_DAYS, null))
                .doesNotThrowAnyException();
    }
}

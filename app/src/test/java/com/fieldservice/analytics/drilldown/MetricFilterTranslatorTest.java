package com.fieldservice.analytics.drilldown;

import com.fieldservice.analytics.internal.drilldown.MetricFilterTranslator;
import com.fieldservice.analytics.web.MetricKey;
import com.fieldservice.analytics.web.WindowKey;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MetricFilterTranslator (WO-168, AC-8).
 *
 * <p>No Spring context — pure unit tests covering every metric family.
 */
class MetricFilterTranslatorTest {

    private MetricFilterTranslator translator;
    private Instant now;

    @BeforeEach
    void setUp() {
        translator = new MetricFilterTranslator();
        now = Instant.parse("2025-06-01T12:00:00Z");
    }

    // ── SLA metrics ───────────────────────────────────────────────────────────

    @Test
    void sla_compliance_rate_filters_closed_completed_within_window() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED);
        assertThat(c.createdFrom()).isEqualTo(now.minus(30, ChronoUnit.DAYS));
        assertThat(c.createdTo()).isEqualTo(now);
        assertThat(c.atRisk()).isNull();
    }

    @Test
    void sla_resolution_mean_same_as_compliance() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_RESOLUTION_MEAN, WindowKey.SEVEN_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED);
        assertThat(c.createdFrom()).isEqualTo(now.minus(7, ChronoUnit.DAYS));
    }

    @Test
    void sla_breach_count_uses_at_risk_predicate() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_BREACH_COUNT, WindowKey.NINETY_DAYS, null, now);

        assertThat(c.atRisk()).isTrue();
        assertThat(c.createdFrom()).isEqualTo(now.minus(90, ChronoUnit.DAYS));
    }

    // ── Quality metrics ───────────────────────────────────────────────────────

    @Test
    void ftf_rate_filters_closed_completed_within_window() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.FTF_RATE, WindowKey.THIRTY_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED);
        assertThat(c.createdFrom()).isNotNull();
    }

    @Test
    void repeat_visit_count_filters_terminal_within_window() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.REPEAT_VISIT_COUNT, WindowKey.THIRTY_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED);
    }

    // ── Backlog metrics ───────────────────────────────────────────────────────

    @Test
    void backlog_open_count_filters_active_states_no_date_range() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.BACKLOG_OPEN_COUNT, WindowKey.THIRTY_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.NEW, WorkOrderState.ASSIGNED,
                WorkOrderState.EN_ROUTE, WorkOrderState.IN_PROGRESS);
        assertThat(c.createdFrom()).isNull();
        assertThat(c.createdTo()).isNull();
    }

    @Test
    void backlog_on_hold_count_filters_on_hold_only() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.BACKLOG_ON_HOLD_COUNT, WindowKey.THIRTY_DAYS, null, now);

        assertThat(c.states()).containsOnly(WorkOrderState.ON_HOLD);
    }

    // ── Workforce metrics ─────────────────────────────────────────────────────

    @Test
    void utilization_rate_filters_terminal_within_window() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.UTILIZATION_RATE, WindowKey.SEVEN_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED);
        assertThat(c.createdFrom()).isEqualTo(now.minus(7, ChronoUnit.DAYS));
    }

    @Test
    void jobs_per_day_filters_terminal_within_window() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.JOBS_PER_DAY, WindowKey.THIRTY_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED);
    }

    @Test
    void workload_balance_cv_filters_active_and_terminal_within_window() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.WORKLOAD_BALANCE_CV, WindowKey.NINETY_DAYS, null, now);

        assertThat(c.states()).containsExactlyInAnyOrder(
                WorkOrderState.COMPLETED, WorkOrderState.CLOSED,
                WorkOrderState.NEW, WorkOrderState.ASSIGNED,
                WorkOrderState.EN_ROUTE, WorkOrderState.IN_PROGRESS);
    }

    // ── All metrics covered ───────────────────────────────────────────────────

    @ParameterizedTest(name = "metric {0} returns non-null criteria")
    @EnumSource(MetricKey.class)
    void all_metric_keys_return_non_null_criteria(MetricKey metric) {
        WorkOrderSearchCriteria c = translator.translate(metric, WindowKey.THIRTY_DAYS, null, now);
        assertThat(c).isNotNull();
    }

    // ── Window boundary ───────────────────────────────────────────────────────

    @Test
    void window_boundary_uses_request_instant_not_db_function() {
        Instant fixed = Instant.parse("2025-01-15T00:00:00Z");
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS, null, fixed);

        assertThat(c.createdFrom()).isEqualTo(Instant.parse("2024-12-16T00:00:00Z"));
        assertThat(c.createdTo()).isEqualTo(fixed);
    }

    // ── Segment handling ──────────────────────────────────────────────────────

    @Test
    void null_segment_produces_no_technician_filter() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS, null, now);
        assertThat(c.assignedTechnicianId()).isNull();
    }

    @Test
    void ALL_segment_produces_no_technician_filter() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS, "ALL", now);
        assertThat(c.assignedTechnicianId()).isNull();
    }

    @Test
    void technician_segment_parses_uuid_into_technician_filter() {
        UUID techId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS,
                "TECHNICIAN:" + techId, now);
        assertThat(c.assignedTechnicianId()).isEqualTo(techId);
    }

    @Test
    void malformed_technician_segment_silently_produces_no_filter() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS,
                "TECHNICIAN:not-a-uuid", now);
        assertThat(c.assignedTechnicianId()).isNull();
    }

    @Test
    void unknown_segment_produces_no_technician_filter() {
        WorkOrderSearchCriteria c = translator.translate(
                MetricKey.SLA_COMPLIANCE_RATE, WindowKey.THIRTY_DAYS,
                "ASSET_CATEGORY_HVAC", now);
        assertThat(c.assignedTechnicianId()).isNull();
    }
}

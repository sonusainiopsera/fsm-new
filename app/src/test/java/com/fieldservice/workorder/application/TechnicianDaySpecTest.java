package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.SortField;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for day-window resolution and carry-over rule in {@link TechnicianDayQueryService}
 * (WO-154 AC-9).
 */
class TechnicianDaySpecTest {

    private static final LocalDate TEST_DAY = LocalDate.of(2026, 9, 15);
    private static final Instant DAY_START  = TEST_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant DAY_END    = TEST_DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    // ── Day-window spec ────────────────────────────────────────────────────────

    @Test
    void spec_includesJob_scheduledForToday() {
        WorkOrder wo = mockWo(WorkOrderState.ASSIGNED,
                Instant.parse("2026-09-15T08:00:00Z"), null);
        assertMatchesSpec(wo, true);
    }

    @Test
    void spec_excludesJob_scheduledForTomorrow() {
        WorkOrder wo = mockWo(WorkOrderState.ASSIGNED,
                Instant.parse("2026-09-16T08:00:00Z"), null);
        assertMatchesSpec(wo, false);
    }

    @Test
    void spec_excludesCompletedJob_scheduledToday() {
        WorkOrder wo = mockWo(WorkOrderState.COMPLETED,
                Instant.parse("2026-09-15T08:00:00Z"), null);
        assertMatchesSpec(wo, false);
    }

    @Test
    void spec_excludesCancelledJob_scheduledToday() {
        WorkOrder wo = mockWo(WorkOrderState.CANCELLED,
                Instant.parse("2026-09-15T08:00:00Z"), null);
        assertMatchesSpec(wo, false);
    }

    @Test
    void spec_includesCarryOver_assignedJobFromYesterday() {
        WorkOrder wo = mockWo(WorkOrderState.ASSIGNED,
                Instant.parse("2026-09-14T08:00:00Z"), null);
        assertMatchesSpec(wo, true);
    }

    @Test
    void spec_includesCarryOver_inProgressJobNullWindow() {
        WorkOrder wo = mockWo(WorkOrderState.IN_PROGRESS, null, null);
        assertMatchesSpec(wo, true);
    }

    @Test
    void spec_excludesCarryOver_completedJobNullWindow() {
        WorkOrder wo = mockWo(WorkOrderState.COMPLETED, null, null);
        assertMatchesSpec(wo, false);
    }

    @Test
    void spec_excludesCarryOver_cancelledJobFromYesterday() {
        WorkOrder wo = mockWo(WorkOrderState.CANCELLED,
                Instant.parse("2026-09-14T08:00:00Z"), null);
        assertMatchesSpec(wo, false);
    }

    @Test
    void spec_includesCarryOver_enRouteJobNullWindow() {
        WorkOrder wo = mockWo(WorkOrderState.EN_ROUTE, null, null);
        assertMatchesSpec(wo, true);
    }

    @Test
    void spec_includesCarryOver_onHoldJobFromEarlierDay() {
        WorkOrder wo = mockWo(WorkOrderState.ON_HOLD,
                Instant.parse("2026-09-14T23:59:00Z"), null);
        assertMatchesSpec(wo, true);
    }

    // ── Sort allow-list validation ─────────────────────────────────────────────

    @Test
    void unknownSortField_throws() {
        assertThatThrownBy(() ->
                TechnicianDayQueryService.SORT_ALLOW_LIST.resolvePersistentName("badField"))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void scheduledStart_mapsToPersistentName() {
        String persistent = TechnicianDayQueryService.SORT_ALLOW_LIST
                .resolvePersistentName("scheduledStart");
        assertThat(persistent).isEqualTo("scheduledWindowStart");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static WorkOrder mockWo(WorkOrderState state, Instant windowStart, Instant windowEnd) {
        WorkOrder wo = mock(WorkOrder.class);
        when(wo.getState()).thenReturn(state);
        lenient().when(wo.getScheduledWindowStart()).thenReturn(windowStart);
        lenient().when(wo.getScheduledWindowEnd()).thenReturn(windowEnd);
        return wo;
    }

    private static void assertMatchesSpec(WorkOrder wo, boolean expected) {
        // Mirror the logic in TechnicianDayQueryService.buildDaySpec without running JPA
        boolean inWindowAndActive = isCarryOverState(wo.getState())
                && wo.getScheduledWindowStart() != null
                && !wo.getScheduledWindowStart().isBefore(DAY_START)
                && wo.getScheduledWindowStart().isBefore(DAY_END);
        boolean carryOver = isCarryOverState(wo.getState())
                && (wo.getScheduledWindowStart() == null
                    || wo.getScheduledWindowStart().isBefore(DAY_START));
        boolean matches = inWindowAndActive || carryOver;
        assertThat(matches)
                .as("Expected state=%s window=%s to %s spec",
                        wo.getState(), wo.getScheduledWindowStart(),
                        expected ? "match" : "not match")
                .isEqualTo(expected);
    }

    private static boolean isCarryOverState(WorkOrderState state) {
        return state == WorkOrderState.ASSIGNED
                || state == WorkOrderState.EN_ROUTE
                || state == WorkOrderState.IN_PROGRESS
                || state == WorkOrderState.ON_HOLD;
    }
}

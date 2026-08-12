package com.fieldservice.analytics.internal.drilldown;

import com.fieldservice.analytics.web.MetricKey;
import com.fieldservice.analytics.web.WindowKey;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Translates a (metricKey, window, segment) tuple into allow-listed {@link WorkOrderSearchCriteria}
 * for the drill-down work order list (WO-168).
 *
 * <p>Translation is purely data-driven and produces a typed criteria object — never a SQL
 * fragment. Unknown or unsupported metric keys are rejected by the calling layer before
 * this class is invoked; callers may assume every {@link MetricKey} constant is handled.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>No string concatenation of user input reaches the query builder.</li>
 *   <li>Segment values are compared against an allow-list; unrecognised values are silently
 *       ignored (the predicate is widened rather than erroring), because the session-based
 *       widget segment may include values not yet covered by a filter path.</li>
 *   <li>Window boundaries are computed from the request instant, not a database function.</li>
 * </ul>
 */
@Component
public class MetricFilterTranslator {

    /**
     * Translates a metric + window + segment into work order search criteria.
     *
     * @param metric  the allow-listed metric key (non-null, already validated at API layer)
     * @param window  the rolling window (non-null, already validated at API layer)
     * @param segment optional segment key; {@code null} or {@code "ALL"} means no segment restriction
     * @param now     the request instant (injected for testability)
     * @return typed search criteria to pass to the work order search service
     */
    public WorkOrderSearchCriteria translate(MetricKey metric, WindowKey window,
                                              String segment, Instant now) {
        Instant windowStart = now.minus(window.days(), ChronoUnit.DAYS);
        return switch (metric) {

            // ── SLA metrics ──────────────────────────────────────────────────────────
            case SLA_COMPLIANCE_RATE, SLA_RESOLUTION_MEAN, SLA_RESOLUTION_MEDIAN ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.COMPLETED, WorkOrderState.CLOSED),
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, null);

            case SLA_BREACH_COUNT ->
                    new WorkOrderSearchCriteria(
                            null,
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, Boolean.TRUE);

            // ── Quality metrics ───────────────────────────────────────────────────────
            case FTF_RATE ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.COMPLETED, WorkOrderState.CLOSED),
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, null);

            case REPEAT_VISIT_COUNT ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.COMPLETED, WorkOrderState.CLOSED),
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, null);

            // ── Backlog metrics ───────────────────────────────────────────────────────
            case BACKLOG_OPEN_COUNT ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.NEW, WorkOrderState.ASSIGNED,
                                    WorkOrderState.EN_ROUTE, WorkOrderState.IN_PROGRESS),
                            null, technicianId(segment), null, null,
                            null, null, null, null, null);

            case BACKLOG_ON_HOLD_COUNT ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.ON_HOLD),
                            null, technicianId(segment), null, null,
                            null, null, null, null, null);

            // ── Workforce metrics ─────────────────────────────────────────────────────
            case UTILIZATION_RATE ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.COMPLETED, WorkOrderState.CLOSED),
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, null);

            case JOBS_PER_DAY ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.COMPLETED, WorkOrderState.CLOSED),
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, null);

            case WORKLOAD_BALANCE_CV ->
                    new WorkOrderSearchCriteria(
                            List.of(WorkOrderState.COMPLETED, WorkOrderState.CLOSED,
                                    WorkOrderState.NEW, WorkOrderState.ASSIGNED,
                                    WorkOrderState.EN_ROUTE, WorkOrderState.IN_PROGRESS),
                            null, technicianId(segment), null, null,
                            windowStart, now, null, null, null);
        };
    }

    /**
     * Parses a segment key of the form {@code TECHNICIAN:<uuid>} into a technician ID filter.
     * Any other segment value (including {@code null}, {@code "ALL"}, or priority-based segments)
     * is not mapped to the assignedTechnicianId field because:
     * <ul>
     *   <li>Priority segments are already captured in the existing criteria.</li>
     *   <li>Team/role segments span multiple technicians and require a different join path
     *       not yet in the criteria schema — we widen rather than error.</li>
     * </ul>
     */
    private static java.util.UUID technicianId(String segment) {
        if (segment == null || segment.isBlank() || "ALL".equalsIgnoreCase(segment)) {
            return null;
        }
        if (segment.startsWith("TECHNICIAN:")) {
            String uuidPart = segment.substring("TECHNICIAN:".length()).trim();
            try {
                return java.util.UUID.fromString(uuidPart);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}

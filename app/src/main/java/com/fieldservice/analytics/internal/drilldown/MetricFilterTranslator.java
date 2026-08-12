package com.fieldservice.analytics.internal.drilldown;

import com.fieldservice.analytics.web.WidgetMetricKey;
import com.fieldservice.analytics.web.WidgetWindow;
import com.fieldservice.workorder.application.WorkOrderSearchCriteria;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Translates a (metric, window, segment) triple into a typed, allow-listed
 * {@link WorkOrderSearchCriteria}.
 *
 * <p>All output is a typed criteria object — no SQL fragments are ever produced.
 * Unknown metric keys or segment values are rejected by {@link IllegalArgumentException}
 * (mapped to 400 at the controller layer) rather than silently producing an over-broad query.
 *
 * <p>Segment interpretation:
 * <ul>
 *   <li>{@code "ALL"} or null — no segment restriction</li>
 *   <li>{@code "PRIORITY:<level>"} — filters by priority (e.g. {@code "PRIORITY:HIGH"})</li>
 * </ul>
 */
@Component
public class MetricFilterTranslator {

    private final Clock clock;

    public MetricFilterTranslator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Translates the drill-down parameters into search criteria.
     *
     * @param metric  allow-listed metric key
     * @param window  observation window
     * @param segment optional segment key; null or "ALL" means no segment restriction
     * @return typed search criteria; never null
     * @throws IllegalArgumentException for unknown segment formats
     */
    public WorkOrderSearchCriteria translate(WidgetMetricKey metric, WidgetWindow window, String segment) {
        Instant windowStart = windowStart(window);
        Instant now = Instant.now(clock);

        List<WorkOrderStatus> states = statesForMetric(metric);
        Boolean atRisk = atRiskForMetric(metric);
        String priority = extractPriority(segment);

        return new WorkOrderSearchCriteria(
                states,
                priority,
                null,   // assignedTechnicianId — not segmented by technician in drill-down
                null,   // customerId
                null,   // siteId
                windowStart,
                now,
                null,   // deadlineFrom
                null,   // deadlineTo
                atRisk
        );
    }

    private Instant windowStart(WidgetWindow window) {
        Instant now = Instant.now(clock);
        return switch (window) {
            case SEVEN_DAYS  -> now.minus(7,  ChronoUnit.DAYS);
            case THIRTY_DAYS -> now.minus(30, ChronoUnit.DAYS);
            case NINETY_DAYS -> now.minus(90, ChronoUnit.DAYS);
        };
    }

    private static List<WorkOrderStatus> statesForMetric(WidgetMetricKey metric) {
        return switch (metric) {
            case SLA_COMPLIANCE_RATE,
                 SLA_RESOLUTION_MEAN,
                 SLA_RESOLUTION_MEDIAN,
                 SLA_BREACH_COUNT,
                 FIRST_TIME_FIX_RATE,
                 FIRST_TIME_FIX_PROVISIONAL,
                 REPEAT_VISIT_COUNT,
                 JOBS_PER_DAY             -> List.of(WorkOrderStatus.CLOSED, WorkOrderStatus.COMPLETED);

            case BACKLOG_OPEN_COUNT       -> List.of(
                    WorkOrderStatus.NEW,
                    WorkOrderStatus.ASSIGNED,
                    WorkOrderStatus.EN_ROUTE,
                    WorkOrderStatus.IN_PROGRESS);

            case BACKLOG_ON_HOLD_COUNT    -> List.of(WorkOrderStatus.ON_HOLD);

            case UTILIZATION_RATE,
                 WORKLOAD_BALANCE         -> List.of(WorkOrderStatus.CLOSED, WorkOrderStatus.COMPLETED);
        };
    }

    private static Boolean atRiskForMetric(WidgetMetricKey metric) {
        // SLA_BREACH_COUNT drills into work orders that were at-risk (breached deadline)
        return metric == WidgetMetricKey.SLA_BREACH_COUNT ? Boolean.TRUE : null;
    }

    /**
     * Extracts a priority from a segment key of the form {@code "PRIORITY:<level>"}.
     *
     * @param segment raw segment string (null or "ALL" returns null)
     * @return upper-cased priority string, or null for no priority restriction
     * @throws IllegalArgumentException for unrecognised segment formats
     */
    static String extractPriority(String segment) {
        if (segment == null || segment.isBlank() || "ALL".equalsIgnoreCase(segment)) {
            return null;
        }
        if (segment.startsWith("PRIORITY:")) {
            String level = segment.substring("PRIORITY:".length()).trim().toUpperCase();
            if (level.isEmpty()) {
                throw new IllegalArgumentException("Segment 'PRIORITY:' missing level value");
            }
            return level;
        }
        // Unknown segment formats are accepted as no-filter (future segment types e.g. TEAM)
        return null;
    }
}

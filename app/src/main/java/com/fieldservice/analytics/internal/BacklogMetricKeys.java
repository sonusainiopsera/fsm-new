package com.fieldservice.analytics.internal;

import java.util.List;

/**
 * Metric key constants for backlog and workload guardrail projections (WO-165).
 */
final class BacklogMetricKeys {

    /** Total count of work orders in any open state. */
    static final String BACKLOG_OPEN_COUNT = "backlog.open.count";

    /** Count of work orders specifically in ON_HOLD state. */
    static final String BACKLOG_ON_HOLD_COUNT = "backlog.on_hold.count";

    /**
     * Workload balance coefficient of variation: population standard deviation of
     * assigned hours per active technician divided by the mean (7-day rolling window).
     *
     * <p>Population CV is used (not sample) to compare consistently across team sizes.
     * Returns NOT_MEANINGFUL when fewer than 3 technicians are active or mean is zero.
     */
    static final String WORKLOAD_BALANCE_CV = "workforce.workload_balance.cv";

    /** All backlog/workload metric keys — marked dirty together on state-change events. */
    static final List<String> ALL = List.of(
            BACKLOG_OPEN_COUNT, BACKLOG_ON_HOLD_COUNT, WORKLOAD_BALANCE_CV);

    /** Metric keys affected by work-order state changes. */
    static final List<String> STATE_CHANGE_KEYS = List.of(
            BACKLOG_OPEN_COUNT, BACKLOG_ON_HOLD_COUNT);

    /** Metric keys affected by assignment / labour-time changes. */
    static final List<String> ASSIGNMENT_KEYS = List.of(WORKLOAD_BALANCE_CV);

    private BacklogMetricKeys() {}
}

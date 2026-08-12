package com.fieldservice.analytics.web;

/**
 * Allow-listed metric keys exposed on the dashboard widget endpoint.
 *
 * <p>Unknown values are rejected with 400 so the API contract is machine-checkable.
 * The {@link #key()} method returns the internal projection storage key.
 */
public enum WidgetMetricKey {

    SLA_COMPLIANCE_RATE     ("sla.compliance.rate"),
    SLA_BREACH_COUNT        ("sla.breach.count"),
    SLA_RESOLUTION_MEAN     ("sla.resolution.mean"),
    SLA_RESOLUTION_MEDIAN   ("sla.resolution.median"),
    FIRST_TIME_FIX_RATE     ("quality.first_time_fix.matured"),
    FIRST_TIME_FIX_PROVISIONAL("quality.first_time_fix.provisional"),
    REPEAT_VISIT_COUNT      ("quality.repeat_visit.count"),
    BACKLOG_OPEN_COUNT      ("backlog.open.count"),
    BACKLOG_ON_HOLD_COUNT   ("backlog.on_hold.count"),
    UTILIZATION_RATE        ("workforce.utilization.rate"),
    JOBS_PER_DAY            ("workforce.jobs_per_day"),
    WORKLOAD_BALANCE        ("workforce.workload_balance.cv");

    private final String key;

    WidgetMetricKey(String key) { this.key = key; }

    public String key() { return key; }
}

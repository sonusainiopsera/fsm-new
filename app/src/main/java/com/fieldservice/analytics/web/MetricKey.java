package com.fieldservice.analytics.web;

/**
 * Allow-listed metric key enum for the dashboard widget API (WO-166).
 *
 * <p>Each constant maps a stable API-facing name to the internal {@code metric_key}
 * stored in {@code kpi_projection}, and declares the display unit for that metric.
 * Unknown values are rejected at the binding layer with a 400 field error.
 */
public enum MetricKey {

    SLA_COMPLIANCE_RATE("sla.compliance.rate", "%"),
    SLA_RESOLUTION_MEAN("sla.resolution.mean", "minutes"),
    SLA_RESOLUTION_MEDIAN("sla.resolution.median", "minutes"),
    SLA_BREACH_COUNT("sla.breach.count", "count"),
    FTF_RATE("quality.first_time_fix.matured", "%"),
    REPEAT_VISIT_COUNT("quality.repeat_visit.count", "count"),
    BACKLOG_OPEN_COUNT("backlog.open.count", "count"),
    BACKLOG_ON_HOLD_COUNT("backlog.on_hold.count", "count"),
    WORKLOAD_BALANCE_CV("workforce.workload_balance.cv", "cv"),
    UTILIZATION_RATE("workforce.utilization.rate", "%"),
    JOBS_PER_DAY("workforce.jobs_per_day", "jobs/day");

    private final String internalKey;
    private final String unit;

    MetricKey(String internalKey, String unit) {
        this.internalKey = internalKey;
        this.unit = unit;
    }

    public String internalKey() { return internalKey; }
    public String unit()        { return unit; }
}

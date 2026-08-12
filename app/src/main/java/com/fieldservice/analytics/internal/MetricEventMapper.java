package com.fieldservice.analytics.internal;

import java.util.Map;
import java.util.Set;

/**
 * Maps outbox event types to the set of KPI metric keys that must be recomputed
 * when an event of that type is consumed.
 *
 * <p>Package-private utility — not a Spring bean, called directly by
 * {@link KpiOutboxConsumer}. Adding a new event-to-metric mapping here
 * automatically routes it through the debounce + refresh pipeline.
 */
final class MetricEventMapper {

    private static final Map<String, Set<String>> MAPPING = Map.ofEntries(
            Map.entry("WORK_ORDER_CREATED",    Set.of("wo.created_count",    "wo.backlog_count",
                                                      "quality.first_time_fix.provisional",
                                                      "quality.unclassifiable.count",
                                                      "backlog.open.count",  "backlog.on_hold.count")),
            Map.entry("WORK_ORDER_TRANSITION", Set.of("wo.completion_rate",  "wo.sla_compliance",
                                                      "wo.first_time_fix_rate", "wo.backlog_count",
                                                      "quality.first_time_fix.matured",
                                                      "quality.first_time_fix.provisional",
                                                      "quality.repeat_visit.count",
                                                      "quality.unclassifiable.count",
                                                      "backlog.open.count",  "backlog.on_hold.count",
                                                      "workforce.workload_balance.cv",
                                                      "sla.compliance.rate", "sla.breach.count",
                                                      "sla.resolution.mean", "sla.resolution.median",
                                                      "workforce.jobs_per_day")),
            Map.entry("ASSIGNMENT_CREATED",    Set.of("wo.backlog_count",    "technician.utilization",
                                                      "backlog.open.count",  "workforce.workload_balance.cv")),
            Map.entry("SLA_BREACH",            Set.of("wo.sla_compliance",
                                                      "sla.compliance.rate", "sla.breach.count")),
            Map.entry("SLA_AT_RISK",           Set.of("wo.sla_compliance")),
            Map.entry("SLA_POLICY_CHANGED",    Set.of("sla.compliance.rate", "sla.breach.count",
                                                      "sla.resolution.mean", "sla.resolution.median")),
            Map.entry("LABOUR_ENTRY_ADDED",    Set.of("technician.utilization",
                                                      "workforce.workload_balance.cv",
                                                      "workforce.utilization.rate",
                                                      "workforce.jobs_per_day")),
            Map.entry("ROSTER_CHANGED",        Set.of("workforce.utilization.rate",
                                                      "workforce.jobs_per_day")),
            Map.entry("TECHNICIAN_STATUS_CHANGED", Set.of("workforce.utilization.rate",
                                                           "workforce.jobs_per_day"))
    );

    private MetricEventMapper() {}

    static Set<String> metricKeysFor(String eventType) {
        return MAPPING.getOrDefault(eventType, Set.of());
    }

    static Set<String> allHandledEventTypes() {
        return MAPPING.keySet();
    }
}

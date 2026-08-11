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

    private static final Map<String, Set<String>> MAPPING = Map.of(
            "WORK_ORDER_CREATED",    Set.of("wo.created_count",    "wo.backlog_count"),
            "WORK_ORDER_TRANSITION", Set.of("wo.completion_rate",  "wo.sla_compliance",
                                            "wo.first_time_fix_rate", "wo.backlog_count"),
            "ASSIGNMENT_CREATED",    Set.of("wo.backlog_count",    "technician.utilization"),
            "SLA_BREACH",            Set.of("wo.sla_compliance"),
            "SLA_AT_RISK",           Set.of("wo.sla_compliance"),
            "LABOUR_ENTRY_ADDED",    Set.of("technician.utilization")
    );

    private MetricEventMapper() {}

    static Set<String> metricKeysFor(String eventType) {
        return MAPPING.getOrDefault(eventType, Set.of());
    }

    static Set<String> allHandledEventTypes() {
        return MAPPING.keySet();
    }
}

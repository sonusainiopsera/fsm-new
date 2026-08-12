package com.fieldservice.sla.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Consumes {@code SlaRiskFlagged} and {@code SlaBreached} outbox events and fans them
 * out to registered SSE emitters via {@link SlaAlertEmitterRegistry}.
 *
 * <p>The outbox payload is a raw JSON string (stored as jsonb). The work order reference,
 * priority, and state are resolved by a lightweight JDBC query.
 *
 * <p>Acknowledgement contract: {@link #handle(DomainEvent)} only marks the outbox row
 * as published when it returns normally. If fan-out fails (exception), the poller retries.
 * Best-effort delivery: if there are no subscribers the event is still acknowledged
 * (having been buffered for Last-Event-ID replay).
 */
@Component
public class SlaAlertFanoutService {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertFanoutService.class);

    static final String EVENT_TYPE_AT_RISK  = "SLA_AT_RISK";
    static final String EVENT_TYPE_BREACHED = "SLA_BREACHED";

    private final SlaAlertEmitterRegistry registry;
    private final SlaAlertReplayBuffer    replayBuffer;
    private final ObjectMapper            objectMapper;
    private final JdbcTemplate            jdbc;
    private final MeterRegistry           meterRegistry;

    public SlaAlertFanoutService(SlaAlertEmitterRegistry registry,
                                  SlaAlertReplayBuffer replayBuffer,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbc,
                                  MeterRegistry meterRegistry) {
        this.registry     = registry;
        this.replayBuffer = replayBuffer;
        this.objectMapper = objectMapper;
        this.jdbc         = jdbc;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Processes a {@code SlaRiskFlagged} outbox event: builds the SSE payload, buffers it,
     * and fans it out to all active emitters.
     */
    public void handleRiskFlagged(DomainEvent event) throws Exception {
        String rawPayload = (String) event.payload();
        JsonNode node = objectMapper.readTree(rawPayload);

        UUID workOrderId = UUID.fromString(node.get("workOrderId").asText());
        WorkOrderSummary wo = lookupWorkOrder(workOrderId);
        if (wo == null) {
            log.warn("sla_fanout_work_order_not_found event_id={} work_order_id={}", event.eventId(), workOrderId);
            return;
        }

        String triggerReason   = node.get("triggerReason").asText("");
        String projectionBasis = node.path("projectionBasis").asText(null);
        int minutesRemaining   = node.path("minutesRemaining").asInt(0);

        Map<String, Object> payload = buildAtRiskPayload(
                workOrderId, wo.reference(), wo.priority(), wo.state(),
                minutesRemaining, triggerReason, projectionBasis);

        String jsonData = objectMapper.writeValueAsString(payload);
        String eventId  = event.eventId().toString();

        replayBuffer.add(eventId, EVENT_TYPE_AT_RISK, jsonData);
        registry.sendToAll(eventId, EVENT_TYPE_AT_RISK, jsonData, meterRegistry);

        log.debug("sla_fanout_at_risk event_id={} work_order_id={}", eventId, workOrderId);
    }

    /**
     * Processes a {@code SlaBreached} outbox event: builds the SSE payload, buffers it,
     * and fans it out to all active emitters.
     */
    public void handleBreached(DomainEvent event) throws Exception {
        String rawPayload = (String) event.payload();
        JsonNode node = objectMapper.readTree(rawPayload);

        UUID workOrderId   = UUID.fromString(node.get("workOrderId").asText());
        WorkOrderSummary wo = lookupWorkOrder(workOrderId);
        if (wo == null) {
            log.warn("sla_fanout_work_order_not_found event_id={} work_order_id={}", event.eventId(), workOrderId);
            return;
        }

        long overrunMinutes = node.path("overrunMinutes").asLong(0);
        String breachType   = node.path("breachType").asText(null);

        Map<String, Object> payload = buildBreachPayload(
                workOrderId, wo.reference(), wo.priority(), wo.state(),
                overrunMinutes, breachType);

        String jsonData = objectMapper.writeValueAsString(payload);
        String eventId  = event.eventId().toString();

        replayBuffer.add(eventId, EVENT_TYPE_BREACHED, jsonData);
        registry.sendToAll(eventId, EVENT_TYPE_BREACHED, jsonData, meterRegistry);

        log.debug("sla_fanout_breached event_id={} work_order_id={}", eventId, workOrderId);
    }

    private Map<String, Object> buildAtRiskPayload(UUID workOrderId, String reference,
                                                     String priority, String state,
                                                     int minutesRemaining, String triggerReason,
                                                     String projectionBasis) {
        return Map.of(
                "workOrderId",       workOrderId.toString(),
                "workOrderReference", reference,
                "priority",          priority,
                "state",             state,
                "minutesRemaining",  minutesRemaining,
                "triggerReason",     triggerReason,
                "projectionBasis",   projectionBasis != null ? projectionBasis : "",
                "advisory",          true,
                "overrunMinutes",    0,
                "reasonCode",        ""
        );
    }

    private Map<String, Object> buildBreachPayload(UUID workOrderId, String reference,
                                                    String priority, String state,
                                                    long overrunMinutes, String breachType) {
        return Map.of(
                "workOrderId",        workOrderId.toString(),
                "workOrderReference", reference,
                "priority",           priority,
                "state",              state,
                "minutesRemaining",   0,
                "triggerReason",      "SLA_BREACHED",
                "projectionBasis",    "",
                "advisory",           true,
                "overrunMinutes",     overrunMinutes,
                "reasonCode",         breachType != null ? breachType : ""
        );
    }

    private WorkOrderSummary lookupWorkOrder(UUID workOrderId) {
        try {
            return jdbc.queryForObject(
                    "SELECT reference, priority, state FROM work_order WHERE id = ?",
                    (rs, row) -> new WorkOrderSummary(
                            rs.getString("reference"),
                            rs.getString("priority"),
                            rs.getString("state")),
                    workOrderId);
        } catch (Exception e) {
            return null;
        }
    }

    record WorkOrderSummary(String reference, String priority, String state) {}
}

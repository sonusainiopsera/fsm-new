package com.fieldservice.sla.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.SlaBreachedPayload;
import com.fieldservice.outbox.payload.SlaRiskFlaggedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import com.fieldservice.sla.api.dto.SlaAlertEventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Outbox consumers that fan out SLA risk and breach events to open SSE streams.
 *
 * <p>Delivery contract:
 * <ul>
 *   <li>If at least one emitter is active and all sends fail, an exception is thrown
 *       so the outbox retries rather than silently acknowledging an undelivered event.</li>
 *   <li>If no emitters are active the event is stored in the replay buffer only and
 *       the outbox row is acknowledged normally.</li>
 *   <li>Idempotency is enforced by {@link IdempotencyGuard} keyed on (eventId, handlerName).</li>
 * </ul>
 *
 * <p>The two inner {@code @Component} EventHandler adapters are the actual Spring beans;
 * this class is also a {@code @Component} so Spring can inject it into those adapters.
 */
@Component
class SlaAlertFanoutService {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertFanoutService.class);
    private static final String HANDLER_RISK    = "SlaAlertFanout.RiskFlagged";
    private static final String HANDLER_BREACHED = "SlaAlertFanout.Breached";

    private final SlaAlertEmitterRegistry emitterRegistry;
    private final SlaAlertReplayBuffer replayBuffer;
    private final IdempotencyGuard idempotencyGuard;
    private final WorkOrderRepository workOrderRepository;
    private final ObjectMapper objectMapper;

    SlaAlertFanoutService(SlaAlertEmitterRegistry emitterRegistry,
                          SlaAlertReplayBuffer replayBuffer,
                          IdempotencyGuard idempotencyGuard,
                          WorkOrderRepository workOrderRepository,
                          ObjectMapper objectMapper) {
        this.emitterRegistry    = emitterRegistry;
        this.replayBuffer       = replayBuffer;
        this.idempotencyGuard   = idempotencyGuard;
        this.workOrderRepository = workOrderRepository;
        this.objectMapper        = objectMapper;
    }

    // ── Private shared fan-out logic ──────────────────────────────────────────

    void handleRiskFlagged(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), HANDLER_RISK)) {
            return;
        }

        SlaRiskFlaggedPayload payload = objectMapper.readValue(
                ctx.payloadJson(), SlaRiskFlaggedPayload.class);

        WorkOrder wo = workOrderRepository.findById(payload.workOrderId()).orElse(null);
        String reference = wo != null ? wo.getReference() : null;
        String priority  = wo != null ? wo.getPriority() != null ? wo.getPriority().name() : null : null;
        String state     = wo != null ? wo.getState() != null ? wo.getState().name() : null : null;

        SlaAlertEventData data = new SlaAlertEventData(
                payload.workOrderId(),
                reference,
                priority,
                state,
                payload.minutesRemaining(),
                payload.triggerReason(),
                payload.projectionBasis(),
                true,
                null,
                null);

        String dataJson = objectMapper.writeValueAsString(data);
        String eventId  = ctx.eventId().toString();

        replayBuffer.add(new SlaAlertReplayBuffer.ReplayEntry(
                eventId, SlaAlertEmitterRegistry.EVENT_SLA_AT_RISK, dataJson, java.time.Instant.now()));

        boolean hadSubscribers = emitterRegistry.hasActiveSubscribers();
        int delivered = emitterRegistry.fanOut(eventId, SlaAlertEmitterRegistry.EVENT_SLA_AT_RISK, dataJson);

        if (hadSubscribers && delivered == 0) {
            log.warn("sla.stream.fanout.no_delivery eventId={} eventType=SLA_AT_RISK traceId={}",
                    eventId, MDC.get("traceId"));
            throw new DeliveryFailedException("Failed to deliver SLA_AT_RISK event " + eventId
                    + " to any eligible subscriber");
        }

        log.debug("sla.stream.fanout.delivered eventId={} eventType=SLA_AT_RISK delivered={}",
                eventId, delivered);
    }

    void handleBreached(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), HANDLER_BREACHED)) {
            return;
        }

        SlaBreachedPayload payload = objectMapper.readValue(
                ctx.payloadJson(), SlaBreachedPayload.class);

        WorkOrder wo = workOrderRepository.findById(payload.workOrderId()).orElse(null);
        String reference = wo != null ? wo.getReference() : null;
        String priority  = wo != null ? wo.getPriority() != null ? wo.getPriority().name() : null : null;
        String state     = wo != null ? wo.getState() != null ? wo.getState().name() : null : null;

        SlaAlertEventData data = new SlaAlertEventData(
                payload.workOrderId(),
                reference,
                priority,
                state,
                null,
                null,
                null,
                false,
                payload.overrunMinutes(),
                null);

        String dataJson = objectMapper.writeValueAsString(data);
        String eventId  = ctx.eventId().toString();

        replayBuffer.add(new SlaAlertReplayBuffer.ReplayEntry(
                eventId, SlaAlertEmitterRegistry.EVENT_SLA_BREACHED, dataJson, java.time.Instant.now()));

        boolean hadSubscribers = emitterRegistry.hasActiveSubscribers();
        int delivered = emitterRegistry.fanOut(eventId, SlaAlertEmitterRegistry.EVENT_SLA_BREACHED, dataJson);

        if (hadSubscribers && delivered == 0) {
            log.warn("sla.stream.fanout.no_delivery eventId={} eventType=SLA_BREACHED traceId={}",
                    eventId, MDC.get("traceId"));
            throw new DeliveryFailedException("Failed to deliver SLA_BREACHED event " + eventId
                    + " to any eligible subscriber");
        }

        log.debug("sla.stream.fanout.delivered eventId={} eventType=SLA_BREACHED delivered={}",
                eventId, delivered);
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    static class DeliveryFailedException extends RuntimeException {
        DeliveryFailedException(String msg) { super(msg); }
    }

    // ── EventHandler adapters ─────────────────────────────────────────────────

    @Component
    static class SlaRiskFlaggedAlertHandler implements EventHandler {
        private final SlaAlertFanoutService fanout;
        SlaRiskFlaggedAlertHandler(SlaAlertFanoutService fanout) { this.fanout = fanout; }

        @Override
        public String getSupportedEventType() { return SlaRiskFlaggedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            fanout.handleRiskFlagged(ctx);
        }
    }

    @Component
    static class SlaBreachedAlertHandler implements EventHandler {
        private final SlaAlertFanoutService fanout;
        SlaBreachedAlertHandler(SlaAlertFanoutService fanout) { this.fanout = fanout; }

        @Override
        public String getSupportedEventType() { return SlaBreachedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            fanout.handleBreached(ctx);
        }
    }
}

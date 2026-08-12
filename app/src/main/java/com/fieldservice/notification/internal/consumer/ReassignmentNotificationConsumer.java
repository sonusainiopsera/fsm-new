package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.TechnicianUnassignedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification consumer for the {@code TechnicianUnassigned} event (US-003, WO-196).
 *
 * <p>Notifies the outgoing technician that they have been reassigned off a work order.
 */
@Component
@Profile("worker")
public class ReassignmentNotificationConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReassignmentNotificationConsumer.class);
    static final String CONSUMER_NAME = "reassignment.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReassignmentNotificationConsumer(NotificationPort notificationPort,
                                             IdempotencyGuard idempotencyGuard,
                                             TemplateRenderer templateRenderer,
                                             DeadLetterService deadLetterService,
                                             JdbcTemplate jdbcTemplate,
                                             ObjectMapper objectMapper,
                                             MeterRegistry meterRegistry) {
        this.notificationPort = notificationPort;
        this.idempotencyGuard  = idempotencyGuard;
        this.templateRenderer  = templateRenderer;
        this.deadLetterService = deadLetterService;
        this.jdbcTemplate      = jdbcTemplate;
        this.objectMapper      = objectMapper;
        this.meterRegistry     = meterRegistry;
    }

    @Override
    public String getSupportedEventType() {
        return TechnicianUnassignedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_NAME)) {
            log.debug("reassignment_notification_idempotent_skip eventId={}", ctx.eventId());
            return;
        }

        try {
            TechnicianUnassignedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), TechnicianUnassignedPayload.class);

            List<UUID> techUserIds = resolveTechnicianUserId(payload.outgoingTechnicianId());
            for (UUID userId : techUserIds) {
                dispatchInApp(ctx.eventId(), payload, userId);
            }

            meterRegistry.counter("notification.fanout.success", "trigger", "reassignment").increment();

        } catch (Exception e) {
            if (DeadLetterService.isDeterministic(e)) {
                deadLetterService.quarantine(ctx.eventId(), CONSUMER_NAME,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        ctx.payloadJson(), ctx.attemptNumber());
                return;
            }
            throw e;
        }
    }

    private void dispatchInApp(UUID eventId, TechnicianUnassignedPayload payload, UUID userId) {
        try {
            Map<String, Object> params = Map.of(
                    "workOrderReference",  payload.workOrderReference(),
                    "reassignmentReason",  payload.reassignmentReason() != null
                            ? payload.reassignmentReason() : "operational requirement"
            );
            TemplateRenderer.RenderedTemplate rendered =
                    templateRenderer.render("workorder.reassigned", NotificationChannel.IN_APP, "en", params);

            NotificationRequest req = new NotificationRequest(
                    eventId, NotificationChannel.IN_APP, userId, null,
                    eventId + ":IN_APP:" + userId,
                    "WORK_ORDER_REASSIGNED",
                    rendered.subject(), rendered.body(), "INFO"
            );
            notificationPort.send(req);
        } catch (Exception ex) {
            log.warn("reassignment_notification_dispatch_failed eventId={} userId={} error={}",
                    eventId, userId, ex.getMessage());
        }
    }

    private List<UUID> resolveTechnicianUserId(UUID technicianId) {
        try {
            return jdbcTemplate.query(
                    "SELECT user_id FROM technician WHERE id = ? AND is_active = true",
                    (rs, row) -> (UUID) rs.getObject("user_id"),
                    technicianId);
        } catch (Exception ex) {
            log.warn("reassignment_notification_resolve_failed techId={} error={}",
                    technicianId, ex.getMessage());
            return List.of();
        }
    }
}

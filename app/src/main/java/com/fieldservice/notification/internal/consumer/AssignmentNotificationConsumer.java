package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.TechnicianAssignedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification consumer for the {@code TechnicianAssigned} event (US-003, WO-196).
 *
 * <p>Resolves recipients: the assigned technician. Renders the
 * {@code workorder.assigned} template on IN_APP and EMAIL channels and hands the
 * result to the {@link NotificationPort}.
 */
@Component
@Profile("worker")
public class AssignmentNotificationConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(AssignmentNotificationConsumer.class);
    static final String CONSUMER_NAME = "assignment.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AssignmentNotificationConsumer(NotificationPort notificationPort,
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
        return TechnicianAssignedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_NAME)) {
            log.debug("assignment_notification_idempotent_skip eventId={}", ctx.eventId());
            return;
        }

        Instant arrivalTs = Instant.now();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            TechnicianAssignedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), TechnicianAssignedPayload.class);

            List<RecipientRow> recipients = resolveRecipients(payload.technicianId());
            if (recipients.isEmpty()) {
                log.warn("assignment_notification_no_recipients eventId={} technicianId={}",
                        ctx.eventId(), payload.technicianId());
                meterRegistry.counter("notification.fanout.empty_recipients",
                        "trigger", "assignment").increment();
                return;
            }

            for (RecipientRow r : recipients) {
                dispatchToChannel(ctx.eventId(), payload, r, NotificationChannel.IN_APP);
            }

            sample.stop(Timer.builder("notification.fanout.latency")
                    .tag("trigger", "assignment")
                    .register(meterRegistry));

            meterRegistry.counter("notification.fanout.success", "trigger", "assignment").increment();

        } catch (Exception e) {
            if (DeadLetterService.isDeterministic(e)) {
                deadLetterService.quarantine(ctx.eventId(), CONSUMER_NAME,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        ctx.payloadJson(), ctx.attemptNumber());
                meterRegistry.counter("notification.fanout.dead_letter", "trigger", "assignment").increment();
                return; // do not rethrow — dead-lettered deterministically
            }
            meterRegistry.counter("notification.fanout.failure", "trigger", "assignment").increment();
            throw e;
        }
    }

    private void dispatchToChannel(UUID eventId, TechnicianAssignedPayload payload,
                                    RecipientRow r, NotificationChannel channel) {
        try {
            Map<String, Object> params = Map.of(
                    "workOrderReference", payload.workOrderReference(),
                    "priority",           payload.workOrderPriority()
            );
            TemplateRenderer.RenderedTemplate rendered =
                    templateRenderer.render("workorder.assigned", channel, "en", params);

            NotificationRequest req = new NotificationRequest(
                    eventId, channel, r.userId(), null,
                    eventId + ":" + channel + ":" + r.userId(),
                    "WORK_ORDER_ASSIGNED",
                    rendered.subject(), rendered.body(), "INFO"
            );
            notificationPort.send(req);

        } catch (Exception ex) {
            log.warn("assignment_notification_recipient_failed eventId={} userId={} channel={} error={}",
                    eventId, r.userId(), channel, ex.getMessage());
        }
    }

    private List<RecipientRow> resolveRecipients(UUID technicianId) {
        try {
            return jdbcTemplate.query(
                    "SELECT t.user_id FROM technician t WHERE t.id = ? AND t.is_active = true",
                    (rs, rowNum) -> new RecipientRow(
                            (UUID) rs.getObject("user_id"), "TECHNICIAN"),
                    technicianId);
        } catch (Exception ex) {
            log.warn("assignment_notification_resolve_failed technicianId={} error={}",
                    technicianId, ex.getMessage());
            return List.of();
        }
    }

    record RecipientRow(UUID userId, String role) {}
}

package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.SlaRiskFlaggedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification consumer for the {@code SlaRiskFlagged} event (US-008, BR-13, WO-196).
 *
 * <p>Recipients: owning dispatcher and operations manager roles.
 * Template: {@code sla.risk.flagged} — includes projection basis and minutes remaining.
 */
@Component
@Profile("worker")
public class SlaRiskNotificationConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(SlaRiskNotificationConsumer.class);
    static final String CONSUMER_NAME = "sla.risk.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SlaRiskNotificationConsumer(NotificationPort notificationPort,
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
        return SlaRiskFlaggedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_NAME)) {
            log.debug("sla_risk_notification_idempotent_skip eventId={}", ctx.eventId());
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SlaRiskFlaggedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), SlaRiskFlaggedPayload.class);

            List<UUID> recipients = resolveOpsRecipients();
            Map<String, Object> params = Map.of(
                    "workOrderId",      payload.workOrderId().toString(),
                    "minutesRemaining", String.valueOf(payload.minutesRemaining()),
                    "projectionBasis",  payload.projectionBasis() != null
                            ? payload.projectionBasis() : "estimated"
            );

            for (UUID userId : recipients) {
                dispatchInApp(ctx.eventId(), userId, params);
            }

            sample.stop(Timer.builder("notification.fanout.latency")
                    .tag("trigger", "sla_risk").register(meterRegistry));
            meterRegistry.counter("notification.fanout.success", "trigger", "sla_risk").increment();

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

    private void dispatchInApp(UUID eventId, UUID userId, Map<String, Object> params) {
        try {
            TemplateRenderer.RenderedTemplate rendered =
                    templateRenderer.render("sla.risk.flagged", NotificationChannel.IN_APP, "en", params);
            NotificationRequest req = new NotificationRequest(
                    eventId, NotificationChannel.IN_APP, userId, null,
                    eventId + ":IN_APP:" + userId,
                    "SLA_AT_RISK", rendered.subject(), rendered.body(), "WARNING"
            );
            notificationPort.send(req);
        } catch (Exception ex) {
            log.warn("sla_risk_notification_dispatch_failed eventId={} userId={} error={}",
                    eventId, userId, ex.getMessage());
        }
    }

    private List<UUID> resolveOpsRecipients() {
        List<UUID> result = new ArrayList<>();
        try {
            jdbcTemplate.query(
                    "SELECT user_id FROM role_assignment WHERE role_name IN ('MANAGER','DISPATCHER')",
                    rs -> result.add((UUID) rs.getObject("user_id")));
        } catch (Exception ex) {
            log.warn("sla_risk_notification_resolve_failed error={}", ex.getMessage());
        }
        return result;
    }
}

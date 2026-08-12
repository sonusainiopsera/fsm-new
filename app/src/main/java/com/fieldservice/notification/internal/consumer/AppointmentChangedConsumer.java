package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.AppointmentChangedPayload;
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
 * Notification consumer for the {@code AppointmentChanged} event (BR-05, WO-196).
 *
 * <p>Recipients: the customer contact(s) associated with the work order's customer account.
 * Template: {@code appointment.changed}.
 */
@Component
@Profile("worker")
public class AppointmentChangedConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentChangedConsumer.class);
    static final String CONSUMER_NAME = "appointment.changed.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AppointmentChangedConsumer(NotificationPort notificationPort,
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
        return AppointmentChangedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_NAME)) {
            log.debug("appointment_changed_idempotent_skip eventId={}", ctx.eventId());
            return;
        }

        try {
            AppointmentChangedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), AppointmentChangedPayload.class);

            List<UUID> contacts = resolveCustomerContacts(payload.customerId());

            Map<String, Object> params = Map.of(
                    "workOrderReference", payload.workOrderReference()
            );

            for (UUID userId : contacts) {
                dispatchInApp(ctx.eventId(), userId, params);
            }

            meterRegistry.counter("notification.fanout.success", "trigger", "appointment_changed").increment();

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
                    templateRenderer.render("appointment.changed", NotificationChannel.IN_APP, "en", params);
            NotificationRequest req = new NotificationRequest(
                    eventId, NotificationChannel.IN_APP, userId, null,
                    eventId + ":IN_APP:" + userId,
                    "APPOINTMENT_CHANGED", rendered.subject(), rendered.body(), "INFO"
            );
            notificationPort.send(req);
        } catch (Exception ex) {
            log.warn("appointment_changed_dispatch_failed eventId={} userId={} error={}",
                    eventId, userId, ex.getMessage());
        }
    }

    private List<UUID> resolveCustomerContacts(UUID customerId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT pau.user_id
                    FROM portal_account_user pau
                    WHERE pau.customer_account_id = ?
                      AND pau.status = 'ACTIVE'
                    """,
                    (rs, row) -> (UUID) rs.getObject("user_id"),
                    customerId);
        } catch (Exception ex) {
            log.warn("appointment_changed_resolve_failed customerId={} error={}",
                    customerId, ex.getMessage());
            return List.of();
        }
    }
}

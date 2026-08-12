package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.WorkOrderDuplicateLinkedPayload;
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
 * Notification consumer for the {@code WorkOrderDuplicateLinked} event (WO-196).
 *
 * <p>Notifies the customer contact that their service request has been linked to an
 * existing open case. Template: {@code workorder.duplicate.linked}.
 */
@Component
@Profile("worker")
public class WorkOrderDuplicateLinkedConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderDuplicateLinkedConsumer.class);
    static final String CONSUMER_NAME = "workorder.duplicate.linked.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public WorkOrderDuplicateLinkedConsumer(NotificationPort notificationPort,
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
        return WorkOrderDuplicateLinkedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_NAME)) {
            log.debug("duplicate_linked_idempotent_skip eventId={}", ctx.eventId());
            return;
        }

        try {
            WorkOrderDuplicateLinkedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), WorkOrderDuplicateLinkedPayload.class);

            List<UUID> contacts = resolveCustomerContacts(payload.customerId());
            Map<String, Object> params = Map.of(
                    "workOrderReference", payload.workOrderReference()
            );

            for (UUID userId : contacts) {
                try {
                    TemplateRenderer.RenderedTemplate rendered =
                            templateRenderer.render("workorder.duplicate.linked", NotificationChannel.IN_APP, "en", params);
                    NotificationRequest req = new NotificationRequest(
                            ctx.eventId(), NotificationChannel.IN_APP, userId, null,
                            ctx.eventId() + ":IN_APP:" + userId,
                            "WO_DUPLICATE_LINKED", rendered.subject(), rendered.body(), "INFO"
                    );
                    notificationPort.send(req);
                } catch (Exception ex) {
                    log.warn("duplicate_linked_dispatch_failed eventId={} userId={} error={}",
                            ctx.eventId(), userId, ex.getMessage());
                }
            }

            meterRegistry.counter("notification.fanout.success", "trigger", "duplicate_linked").increment();

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

    private List<UUID> resolveCustomerContacts(UUID customerId) {
        try {
            return jdbcTemplate.query(
                    "SELECT user_id FROM portal_account_user WHERE customer_account_id = ? AND status = 'ACTIVE'",
                    (rs, row) -> (UUID) rs.getObject("user_id"),
                    customerId);
        } catch (Exception ex) {
            log.warn("duplicate_linked_resolve_failed customerId={} error={}", customerId, ex.getMessage());
            return List.of();
        }
    }
}

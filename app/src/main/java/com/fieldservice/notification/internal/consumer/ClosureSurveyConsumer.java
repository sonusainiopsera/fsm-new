package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
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
 * Notification consumer that triggers the CSAT survey when a work order is CLOSED
 * (business process 5.4, objective O5, WO-196).
 *
 * <p>This consumer handles the same {@code WorkOrderStateChanged} event type as
 * {@link CustomerStatusChangeConsumer} but is registered under a different consumer name
 * and only processes the {@code CLOSED} transition to avoid double-counting the idempotency
 * guard.
 *
 * <p>Two separate EventHandler beans are registered so each has its own idempotency key
 * — identical to the pattern used by {@code CertificationAlertConsumer}.
 */
@Component
@Profile("worker")
public class ClosureSurveyConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClosureSurveyConsumer.class);

    static final String CONSUMER_NAME_SURVEY   = "workorder.closure.survey.notification";
    static final String CONSUMER_NAME_REJECTED = "workorder.cancelled.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ClosureSurveyConsumer(NotificationPort notificationPort,
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

    /** EventHandler for CSAT survey on CLOSED state. */
    @Component
    @Profile("worker")
    class SurveyHandler implements EventHandler {

        @Override
        public String getSupportedEventType() {
            return WorkOrderStateChangedPayload.EVENT_TYPE;
        }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            processTransition(ctx, "CLOSED", "workorder.closed.survey",
                    "WO_CSAT_SURVEY", CONSUMER_NAME_SURVEY, "closure_survey");
        }
    }

    /** EventHandler for CANCELLED/rejected work orders. */
    @Component
    @Profile("worker")
    class CancelledHandler implements EventHandler {

        @Override
        public String getSupportedEventType() {
            return WorkOrderStateChangedPayload.EVENT_TYPE;
        }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            processTransition(ctx, "CANCELLED", "workorder.rejected",
                    "WO_CANCELLED", CONSUMER_NAME_REJECTED, "wo_cancelled");
        }
    }

    private void processTransition(EventHandlerContext ctx, String requiredState,
                                    String templateKey, String category,
                                    String consumerName, String trigger) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), consumerName)) {
            log.debug("{}_idempotent_skip eventId={}", trigger, ctx.eventId());
            return;
        }

        try {
            WorkOrderStateChangedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), WorkOrderStateChangedPayload.class);

            if (!requiredState.equals(payload.toState())) {
                return; // Not the state we handle
            }

            List<UUID> contacts = resolveCustomerContacts(payload.workOrderId());
            Map<String, Object> params = Map.of(
                    "workOrderReference",
                    payload.workOrderId().toString().substring(0, 8).toUpperCase()
            );

            for (UUID userId : contacts) {
                try {
                    TemplateRenderer.RenderedTemplate rendered =
                            templateRenderer.render(templateKey, NotificationChannel.IN_APP, "en", params);
                    NotificationRequest req = new NotificationRequest(
                            ctx.eventId(), NotificationChannel.IN_APP, userId, null,
                            ctx.eventId() + ":" + consumerName + ":" + userId,
                            category, rendered.subject(), rendered.body(), "INFO"
                    );
                    notificationPort.send(req);
                } catch (Exception ex) {
                    log.warn("{}_dispatch_failed eventId={} userId={} error={}",
                            trigger, ctx.eventId(), userId, ex.getMessage());
                }
            }

            meterRegistry.counter("notification.fanout.success", "trigger", trigger).increment();

        } catch (Exception e) {
            if (DeadLetterService.isDeterministic(e)) {
                deadLetterService.quarantine(ctx.eventId(), consumerName,
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        ctx.payloadJson(), ctx.attemptNumber());
                return;
            }
            throw e;
        }
    }

    private List<UUID> resolveCustomerContacts(UUID workOrderId) {
        try {
            return jdbcTemplate.query("""
                    SELECT pau.user_id
                    FROM work_order wo
                    JOIN portal_account_user pau ON pau.customer_account_id = wo.customer_account_id
                    WHERE wo.id = ? AND pau.status = 'ACTIVE'
                    """,
                    (rs, row) -> (UUID) rs.getObject("user_id"),
                    workOrderId);
        } catch (Exception ex) {
            log.warn("closure_survey_resolve_failed workOrderId={} error={}", workOrderId, ex.getMessage());
            return List.of();
        }
    }
}

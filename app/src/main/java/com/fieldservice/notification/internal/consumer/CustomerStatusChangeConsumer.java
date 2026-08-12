package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.CustomerFacingStateLabels;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification consumer for customer-visible work order state changes (US-006, US-010, WO-196).
 *
 * <p>Only states present in {@link CustomerFacingStateLabels} trigger a notification.
 * Internal state names are never surfaced — the customer-appropriate label is looked up
 * from the labels map before rendering (AC-8).
 *
 * <p>Recipients: the customer contact linked to the work order's customer account.
 */
@Component
@Profile("worker")
public class CustomerStatusChangeConsumer implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerStatusChangeConsumer.class);
    static final String CONSUMER_NAME = "customer.status.change.notification";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final TemplateRenderer templateRenderer;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public CustomerStatusChangeConsumer(NotificationPort notificationPort,
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
        return WorkOrderStateChangedPayload.EVENT_TYPE;
    }

    @Override
    public void handle(EventHandlerContext ctx) throws Exception {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_NAME)) {
            log.debug("customer_status_change_idempotent_skip eventId={}", ctx.eventId());
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            WorkOrderStateChangedPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), WorkOrderStateChangedPayload.class);

            // Skip states not visible to customers
            if (!CustomerFacingStateLabels.isCustomerVisible(payload.toState())) {
                log.debug("customer_status_change_state_not_visible eventId={} toState={}",
                        ctx.eventId(), payload.toState());
                return;
            }

            String statusLabel = CustomerFacingStateLabels.labelFor(payload.toState())
                    .orElseThrow(() -> new IllegalStateException("Label missing for visible state: " + payload.toState()));

            List<CustomerContact> contacts = resolveCustomerContacts(payload.workOrderId());
            if (contacts.isEmpty()) {
                log.warn("customer_status_change_no_contacts eventId={} workOrderId={}",
                        ctx.eventId(), payload.workOrderId());
                return;
            }

            for (CustomerContact contact : contacts) {
                dispatchToCustomer(ctx.eventId(), payload, statusLabel, contact);
            }

            sample.stop(Timer.builder("notification.fanout.latency")
                    .tag("trigger", "customer_status_change").register(meterRegistry));
            meterRegistry.counter("notification.fanout.success", "trigger", "customer_status_change").increment();

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

    private void dispatchToCustomer(UUID eventId, WorkOrderStateChangedPayload payload,
                                     String statusLabel, CustomerContact contact) {
        try {
            // workOrderReference is not in the payload but in the WO table — use workOrderId as reference
            Map<String, Object> params = Map.of(
                    "workOrderReference", payload.workOrderId().toString().substring(0, 8).toUpperCase(),
                    "statusLabel",        statusLabel
            );
            TemplateRenderer.RenderedTemplate rendered =
                    templateRenderer.render("workorder.status.customer", NotificationChannel.IN_APP, "en", params);
            NotificationRequest req = new NotificationRequest(
                    eventId, NotificationChannel.IN_APP, contact.userId(), null,
                    eventId + ":IN_APP:" + contact.userId(),
                    "WO_STATUS_CHANGE", rendered.subject(), rendered.body(), "INFO"
            );
            notificationPort.send(req);
        } catch (Exception ex) {
            log.warn("customer_status_change_dispatch_failed eventId={} userId={} error={}",
                    eventId, contact.userId(), ex.getMessage());
        }
    }

    private List<CustomerContact> resolveCustomerContacts(UUID workOrderId) {
        try {
            return jdbcTemplate.query("""
                    SELECT pau.user_id
                    FROM work_order wo
                    JOIN portal_account_user pau ON pau.customer_account_id = wo.customer_account_id
                    WHERE wo.id = ?
                      AND pau.status = 'ACTIVE'
                    """,
                    (rs, row) -> new CustomerContact((UUID) rs.getObject("user_id")),
                    workOrderId);
        } catch (Exception ex) {
            log.warn("customer_status_change_resolve_failed workOrderId={} error={}",
                    workOrderId, ex.getMessage());
            return List.of();
        }
    }

    record CustomerContact(UUID userId) {}
}

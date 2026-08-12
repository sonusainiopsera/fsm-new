package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.CustomerFacingStateLabels;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.platform.outbox.EventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Notification consumer for {@code WORK_ORDER_STATE_CHANGED} events — customer-visible only.
 *
 * <p>Sends customer-facing notifications using approved label mapping so no internal state
 * name is ever exposed. Also handles CSAT survey notification when state transitions to CLOSED.
 * Resolves portal account users associated with the work order's customer.
 *
 * <p>No dependency on workorder.internal or dispatch.internal.
 */
@Profile("worker")
@Configuration
class CustomerStatusChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(CustomerStatusChangeConsumer.class);

    static final String CONSUMER_NAME      = "CustomerStatusChangeConsumer";
    static final String CONSUMER_CSAT      = "CsatSurveyNotificationConsumer";
    static final String EVENT_TYPE         = "WORK_ORDER_STATE_CHANGED";
    static final String TEMPLATE_STATUS    = "customer_status_change";
    static final String TEMPLATE_CSAT      = "csat_survey";
    static final String CATEGORY_STATUS    = "CUSTOMER_STATUS_UPDATE";
    static final String CATEGORY_CSAT      = "CSAT_SURVEY";

    // States that should trigger a customer notification
    private static final Set<String> CUSTOMER_VISIBLE_STATES = Set.of(
            "ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD", "COMPLETED", "CLOSED", "CANCELLED"
    );

    private final NotificationPort         notificationPort;
    private final TemplateRenderer         templateRenderer;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final DeadLetterService        deadLetterService;
    private final ObjectMapper             objectMapper;
    private final JdbcTemplate             jdbc;
    private final Counter                  statusCounter;
    private final Counter                  csatCounter;
    private final Timer                    handoffTimer;

    CustomerStatusChangeConsumer(NotificationPort notificationPort,
                                  TemplateRenderer templateRenderer,
                                  ConsumerIdempotencyGuard idempotencyGuard,
                                  DeadLetterService deadLetterService,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbc,
                                  MeterRegistry meterRegistry) {
        this.notificationPort    = notificationPort;
        this.templateRenderer    = templateRenderer;
        this.idempotencyGuard    = idempotencyGuard;
        this.deadLetterService   = deadLetterService;
        this.objectMapper        = objectMapper;
        this.jdbc                = jdbc;
        this.statusCounter       = Counter.builder("notification_trigger_total")
                .tag("trigger", "customer_status_change").register(meterRegistry);
        this.csatCounter         = Counter.builder("notification_trigger_total")
                .tag("trigger", "csat_survey").register(meterRegistry);
        this.handoffTimer        = Timer.builder("notification_handoff_latency_seconds")
                .tag("trigger", "customer_status").register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consume(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_NAME)) {
            log.debug("customer_status_consumer_idempotent_skip event_id={}", event.eventId());
            return;
        }

        Instant arrival = Instant.now();
        Map<String, Object> payload = parsePayload(event);
        String workOrderIdStr = String.valueOf(payload.getOrDefault("workOrderId", "unknown"));
        String toState        = String.valueOf(payload.getOrDefault("toState", ""));
        String holdReason     = (String) payload.get("reason");

        if (!CUSTOMER_VISIBLE_STATES.contains(toState)) {
            return; // not a customer-visible transition
        }

        String statusLabel       = CustomerFacingStateLabels.label(toState, holdReason);
        String statusDescription = CustomerFacingStateLabels.description(toState, holdReason);

        List<UUID> customers = resolveCustomerPortalUsers(event.aggregateId());
        if (customers.isEmpty()) {
            log.debug("customer_status_no_portal_users event_id={} work_order={}",
                    event.eventId(), workOrderIdStr);
        }

        TemplateRenderer.RenderedTemplate rendered;
        try {
            rendered = templateRenderer.render(TEMPLATE_STATUS, NotificationChannel.IN_APP, "en",
                    Map.of("statusLabel", statusLabel, "statusDescription", statusDescription));
        } catch (Exception ex) {
            if (!DeadLetterService.isDeterministic(ex)) { throw ex; }
            deadLetterService.quarantine(event.eventId(), CONSUMER_NAME,
                    "Template render failed: " + ex.getMessage(), "key=" + TEMPLATE_STATUS, 1);
            return;
        }

        for (UUID recipientUserId : customers) {
            dispatchToUser(event.eventId(), recipientUserId, rendered, CATEGORY_STATUS, "MEDIUM");
        }
        if (!customers.isEmpty()) {
            statusCounter.increment();
        }

        // CSAT survey notification on CLOSED
        if ("CLOSED".equals(toState)) {
            dispatchCsatSurveyNotification(event, workOrderIdStr, customers);
        }

        handoffTimer.record(java.time.Duration.between(arrival, Instant.now()));
    }

    private void dispatchCsatSurveyNotification(DomainEvent event, String workOrderRef,
                                                  List<UUID> customers) {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_CSAT)) {
            return;
        }
        TemplateRenderer.RenderedTemplate csatRendered;
        try {
            csatRendered = templateRenderer.render(TEMPLATE_CSAT, NotificationChannel.IN_APP, "en",
                    Map.of("workOrderRef", workOrderRef));
        } catch (Exception ex) {
            if (!DeadLetterService.isDeterministic(ex)) { throw ex; }
            deadLetterService.quarantine(event.eventId(), CONSUMER_CSAT,
                    "CSAT template render failed: " + ex.getMessage(), "key=" + TEMPLATE_CSAT, 1);
            return;
        }

        for (UUID recipientUserId : customers) {
            dispatchToUser(event.eventId(), recipientUserId, csatRendered, CATEGORY_CSAT, "LOW");
        }
        if (!customers.isEmpty()) {
            csatCounter.increment();
        }
    }

    private void dispatchToUser(UUID eventId, UUID recipientUserId,
                                  TemplateRenderer.RenderedTemplate rendered,
                                  String category, String severity) {
        try {
            notificationPort.send(new NotificationRequest(
                    eventId, NotificationChannel.IN_APP, recipientUserId, "",
                    rendered.subject(), rendered.body(), category, severity));
        } catch (DataIntegrityViolationException ex) {
            log.debug("customer_status_idempotent event_id={} recipient={}", eventId, recipientUserId);
        } catch (Exception ex) {
            log.warn("customer_status_send_failed event_id={} recipient={}", eventId, recipientUserId, ex);
        }
    }

    private List<UUID> resolveCustomerPortalUsers(UUID workOrderId) {
        return jdbc.queryForList(
                "SELECT pau.user_id FROM portal_account_user pau " +
                "JOIN site s ON s.customer_id = pau.account_id " +
                "JOIN work_order wo ON wo.site_id = s.id " +
                "WHERE wo.id = ? AND pau.status = 'ACTIVE'",
                UUID.class, workOrderId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof Map) {
            return (Map<String, Object>) event.payload();
        }
        return objectMapper.convertValue(event.payload(), new TypeReference<>() {});
    }

    @Bean
    EventHandler customerStatusChangeNotificationHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_TYPE; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }
}

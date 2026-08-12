package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.platform.outbox.EventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification consumer for {@code WORK_ORDER_DUPLICATE_LINKED} events.
 *
 * <p>Notifies the customer that their request has been merged with a surviving work order.
 * Uses customer-appropriate label — no internal state names or another customer's data is exposed.
 */
@Profile("worker")
@Configuration
class DuplicateLinkedNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(DuplicateLinkedNotificationConsumer.class);

    static final String CONSUMER_NAME = "DuplicateLinkedNotificationConsumer";
    static final String EVENT_TYPE    = "WORK_ORDER_DUPLICATE_LINKED";
    static final String TEMPLATE_KEY  = "request_rejected";
    static final String CATEGORY      = "PORTAL_REQUEST_UPDATE";

    private final NotificationPort         notificationPort;
    private final TemplateRenderer         templateRenderer;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final DeadLetterService        deadLetterService;
    private final ObjectMapper             objectMapper;
    private final JdbcTemplate             jdbc;
    private final Counter                  triggerCounter;

    DuplicateLinkedNotificationConsumer(NotificationPort notificationPort,
                                         TemplateRenderer templateRenderer,
                                         ConsumerIdempotencyGuard idempotencyGuard,
                                         DeadLetterService deadLetterService,
                                         ObjectMapper objectMapper,
                                         JdbcTemplate jdbc,
                                         MeterRegistry meterRegistry) {
        this.notificationPort  = notificationPort;
        this.templateRenderer  = templateRenderer;
        this.idempotencyGuard  = idempotencyGuard;
        this.deadLetterService = deadLetterService;
        this.objectMapper      = objectMapper;
        this.jdbc              = jdbc;
        this.triggerCounter    = Counter.builder("notification_trigger_total")
                .tag("trigger", "duplicate_linked").register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consume(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_NAME)) {
            log.debug("duplicate_linked_idempotent_skip event_id={}", event.eventId());
            return;
        }

        Map<String, Object> payload = parsePayload(event);
        String survivingId = String.valueOf(payload.getOrDefault("survivingWorkOrderId", "unknown"));
        String reason      = String.valueOf(payload.getOrDefault("reason", "duplicate request"));
        String sourceId    = String.valueOf(payload.getOrDefault("sourceWorkOrderId", "unknown"));

        List<UUID> customers = resolveCustomerPortalUsers(event.aggregateId());

        TemplateRenderer.RenderedTemplate rendered;
        try {
            rendered = templateRenderer.render(TEMPLATE_KEY, NotificationChannel.IN_APP, "en",
                    Map.of("survivingRef", survivingId, "reason", reason));
        } catch (Exception ex) {
            if (!DeadLetterService.isDeterministic(ex)) { throw ex; }
            deadLetterService.quarantine(event.eventId(), CONSUMER_NAME,
                    "Template render failed: " + ex.getMessage(), "key=" + TEMPLATE_KEY, 1);
            return;
        }

        for (UUID recipientUserId : customers) {
            try {
                notificationPort.send(new NotificationRequest(
                        event.eventId(), NotificationChannel.IN_APP, recipientUserId, "",
                        rendered.subject(), rendered.body(), CATEGORY, "MEDIUM"));
            } catch (DataIntegrityViolationException ex) {
                log.debug("duplicate_linked_idempotent event_id={} recipient={}",
                        event.eventId(), recipientUserId);
            } catch (Exception ex) {
                log.warn("duplicate_linked_send_failed event_id={} recipient={}",
                        event.eventId(), recipientUserId, ex);
            }
        }
        triggerCounter.increment();
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
    EventHandler duplicateLinkedNotificationHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_TYPE; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }
}

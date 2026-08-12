package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Notification consumers for {@code TECHNICIAN_ASSIGNED} and {@code TECHNICIAN_ASSIGNMENT_REVOKED}.
 *
 * <p>Resolves the assigned technician's user account and dispatches via the TemplateRenderer.
 * Idempotent on event_id; duplicate delivery blocked by the DB uniqueness constraint.
 * Never imports from dispatch.internal or workorder.internal.
 */
@Profile("worker")
@Configuration
class WorkOrderAssignmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderAssignmentConsumer.class);

    static final String CONSUMER_ASSIGNED  = "WorkOrderAssignmentConsumer";
    static final String CONSUMER_REVOKED   = "WorkOrderRevocationConsumer";
    static final String EVENT_ASSIGNED     = "TECHNICIAN_ASSIGNED";
    static final String EVENT_REVOKED      = "TECHNICIAN_ASSIGNMENT_REVOKED";
    static final String TEMPLATE_ASSIGNED  = "assignment_notification";
    static final String TEMPLATE_REVOKED   = "reassignment_notification";
    static final String CATEGORY           = "WORK_ORDER_ASSIGNMENT";

    private final NotificationPort         notificationPort;
    private final TemplateRenderer         templateRenderer;
    private final TechnicianRepository     technicianRepository;
    private final AppUserRepository        appUserRepository;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final DeadLetterService        deadLetterService;
    private final ObjectMapper             objectMapper;
    private final Counter                  assignedCounter;
    private final Counter                  revokedCounter;
    private final Timer                    handoffTimer;

    WorkOrderAssignmentConsumer(NotificationPort notificationPort,
                                 TemplateRenderer templateRenderer,
                                 TechnicianRepository technicianRepository,
                                 AppUserRepository appUserRepository,
                                 ConsumerIdempotencyGuard idempotencyGuard,
                                 DeadLetterService deadLetterService,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.notificationPort    = notificationPort;
        this.templateRenderer    = templateRenderer;
        this.technicianRepository = technicianRepository;
        this.appUserRepository   = appUserRepository;
        this.idempotencyGuard    = idempotencyGuard;
        this.deadLetterService   = deadLetterService;
        this.objectMapper        = objectMapper;
        this.assignedCounter     = Counter.builder("notification_trigger_total")
                .tag("trigger", "assignment_notification").register(meterRegistry);
        this.revokedCounter      = Counter.builder("notification_trigger_total")
                .tag("trigger", "reassignment_notification").register(meterRegistry);
        this.handoffTimer        = Timer.builder("notification_handoff_latency_seconds")
                .tag("trigger", "assignment").register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consumeAssigned(DomainEvent event) throws Exception {
        dispatch(event, false, CONSUMER_ASSIGNED, TEMPLATE_ASSIGNED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consumeRevoked(DomainEvent event) throws Exception {
        dispatch(event, true, CONSUMER_REVOKED, TEMPLATE_REVOKED);
    }

    private void dispatch(DomainEvent event, boolean isRevocation,
                          String consumer, String templateKey) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), consumer)) {
            log.debug("assignment_consumer_idempotent_skip event_id={} consumer={}", event.eventId(), consumer);
            return;
        }

        Instant arrival = Instant.now();
        Map<String, Object> payload = parsePayload(event);
        String technicianIdStr = (String) payload.get("technicianId");
        String workOrderIdStr  = String.valueOf(payload.getOrDefault("workOrderId", "unknown"));

        if (technicianIdStr == null) {
            deadLetterService.quarantine(event.eventId(), consumer,
                    "Missing technicianId in payload", "eventType=" + event.eventType(), 1);
            return;
        }

        UUID technicianId = UUID.fromString(technicianIdStr);
        Technician technician = technicianRepository.findById(technicianId).orElse(null);
        if (technician == null) {
            deadLetterService.quarantine(event.eventId(), consumer,
                    "Technician not found: " + technicianId, "technicianId=" + technicianId, 1);
            return;
        }

        UUID recipientUserId = technician.getUserId();
        String contact = appUserRepository.findById(recipientUserId)
                .map(AppUser::getEmail).orElse("");

        TemplateRenderer.RenderedTemplate rendered;
        try {
            rendered = templateRenderer.render(templateKey, NotificationChannel.IN_APP, "en",
                    Map.of("workOrderRef", workOrderIdStr));
        } catch (Exception ex) {
            if (!DeadLetterService.isDeterministic(ex)) { throw ex; }
            deadLetterService.quarantine(event.eventId(), consumer,
                    "Template render failed: " + ex.getMessage(), "key=" + templateKey, 1);
            return;
        }

        try {
            DeliveryOutcome outcome = notificationPort.send(new NotificationRequest(
                    event.eventId(), NotificationChannel.IN_APP, recipientUserId, contact,
                    rendered.subject(), rendered.body(), CATEGORY, "MEDIUM"));
            if (isRevocation) revokedCounter.increment(); else assignedCounter.increment();
            log.debug("assignment_notification_sent event_id={} outcome={}", event.eventId(), outcome);
        } catch (DataIntegrityViolationException ex) {
            log.debug("assignment_idempotent_dup event_id={} recipient={}", event.eventId(), recipientUserId);
        }

        handoffTimer.record(java.time.Duration.between(arrival, Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof Map) {
            return (Map<String, Object>) event.payload();
        }
        return objectMapper.convertValue(event.payload(), new TypeReference<>() {});
    }

    @Bean
    EventHandler technicianAssignedNotificationHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_ASSIGNED; }
            @Override public void handle(DomainEvent event) throws Exception { consumeAssigned(event); }
        };
    }

    @Bean
    EventHandler technicianRevocationNotificationHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_REVOKED; }
            @Override public void handle(DomainEvent event) throws Exception { consumeRevoked(event); }
        };
    }
}

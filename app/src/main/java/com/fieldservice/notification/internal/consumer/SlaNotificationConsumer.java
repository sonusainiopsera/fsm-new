package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

/**
 * Notification consumers for {@code SlaRiskFlagged} and {@code SlaBreached} outbox events.
 *
 * <p>Resolves dispatcher and operations manager recipients by role. Renders versioned templates
 * with projection basis (at-risk) or overrun plus reason code (breach). Idempotent; the
 * SlaEscalationConsumer in the SLA module handles a complementary escalation path — this
 * consumer provides template-based IN_APP notifications using the notification fan-out layer.
 *
 * <p>No compile-time dependency on sla.internal or workorder.internal — reads recipients
 * by role query only.
 */
@Profile("worker")
@Configuration
class SlaNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaNotificationConsumer.class);

    static final String CONSUMER_AT_RISK = "SlaAtRiskNotificationConsumer";
    static final String CONSUMER_BREACH  = "SlaBreachNotificationConsumer";
    static final String EVENT_AT_RISK    = "SlaRiskFlagged";
    static final String EVENT_BREACH     = "SlaBreached";
    static final String TEMPLATE_AT_RISK = "sla_at_risk";
    static final String TEMPLATE_BREACH  = "sla_breach";
    static final String CATEGORY         = "SLA_ALERT";

    private final NotificationPort         notificationPort;
    private final TemplateRenderer         templateRenderer;
    private final AppUserRepository        appUserRepository;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final DeadLetterService        deadLetterService;
    private final ObjectMapper             objectMapper;
    private final JdbcTemplate             jdbc;
    private final Counter                  atRiskCounter;
    private final Counter                  breachCounter;
    private final Timer                    handoffTimer;

    SlaNotificationConsumer(NotificationPort notificationPort,
                             TemplateRenderer templateRenderer,
                             AppUserRepository appUserRepository,
                             ConsumerIdempotencyGuard idempotencyGuard,
                             DeadLetterService deadLetterService,
                             ObjectMapper objectMapper,
                             JdbcTemplate jdbc,
                             MeterRegistry meterRegistry) {
        this.notificationPort  = notificationPort;
        this.templateRenderer  = templateRenderer;
        this.appUserRepository = appUserRepository;
        this.idempotencyGuard  = idempotencyGuard;
        this.deadLetterService = deadLetterService;
        this.objectMapper      = objectMapper;
        this.jdbc              = jdbc;
        this.atRiskCounter     = Counter.builder("notification_trigger_total")
                .tag("trigger", "sla_at_risk").register(meterRegistry);
        this.breachCounter     = Counter.builder("notification_trigger_total")
                .tag("trigger", "sla_breach").register(meterRegistry);
        this.handoffTimer      = Timer.builder("notification_handoff_latency_seconds")
                .tag("trigger", "sla").register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consumeAtRisk(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_AT_RISK)) {
            log.debug("sla_at_risk_idempotent_skip event_id={}", event.eventId());
            return;
        }
        Instant arrival = Instant.now();
        Map<String, Object> payload = parsePayload(event);
        String workOrderId   = String.valueOf(payload.getOrDefault("workOrderId", "unknown"));
        String projectionBasis = String.valueOf(payload.getOrDefault("projectionBasis", "unknown"));

        Map<String, String> params = Map.of(
                "workOrderRef", workOrderId,
                "projectionBasis", projectionBasis);

        dispatchToOperators(event, CONSUMER_AT_RISK, TEMPLATE_AT_RISK, params, "HIGH");
        atRiskCounter.increment();
        handoffTimer.record(java.time.Duration.between(arrival, Instant.now()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consumeBreach(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_BREACH)) {
            log.debug("sla_breach_idempotent_skip event_id={}", event.eventId());
            return;
        }
        Instant arrival = Instant.now();
        Map<String, Object> payload = parsePayload(event);
        String workOrderId      = String.valueOf(payload.getOrDefault("workOrderId", "unknown"));
        String overrunMinutes   = String.valueOf(payload.getOrDefault("overrunMinutes", "0"));
        String breachReasonCode = String.valueOf(payload.getOrDefault("breachType", "UNKNOWN"));

        Map<String, String> params = Map.of(
                "workOrderRef", workOrderId,
                "overrunMinutes", overrunMinutes,
                "breachReasonCode", breachReasonCode);

        dispatchToOperators(event, CONSUMER_BREACH, TEMPLATE_BREACH, params, "HIGH");
        breachCounter.increment();
        handoffTimer.record(java.time.Duration.between(arrival, Instant.now()));
    }

    private void dispatchToOperators(DomainEvent event, String consumer, String templateKey,
                                      Map<String, String> params, String severity) {
        TemplateRenderer.RenderedTemplate rendered;
        try {
            rendered = templateRenderer.render(templateKey, NotificationChannel.IN_APP, "en", params);
        } catch (Exception ex) {
            if (!DeadLetterService.isDeterministic(ex)) { throw ex; }
            deadLetterService.quarantine(event.eventId(), consumer,
                    "Template render failed: " + ex.getMessage(), "key=" + templateKey, 1);
            return;
        }

        List<UUID> recipients = resolveOperatorUserIds();
        for (UUID recipientUserId : recipients) {
            String contact = appUserRepository.findById(recipientUserId)
                    .map(u -> u.getEmail()).orElse("");
            try {
                notificationPort.send(new NotificationRequest(
                        event.eventId(), NotificationChannel.IN_APP, recipientUserId, contact,
                        rendered.subject(), rendered.body(), CATEGORY, severity));
            } catch (DataIntegrityViolationException ex) {
                log.debug("sla_notification_idempotent event_id={} recipient={}",
                        event.eventId(), recipientUserId);
            } catch (Exception ex) {
                log.warn("sla_notification_send_failed event_id={} recipient={}",
                        event.eventId(), recipientUserId, ex);
            }
        }
    }

    private List<UUID> resolveOperatorUserIds() {
        return jdbc.queryForList(
                "SELECT DISTINCT ra.user_id FROM role_assignment ra " +
                "JOIN app_user u ON u.id = ra.user_id " +
                "WHERE ra.role_name IN ('DISPATCHER','MANAGER') AND u.active = TRUE",
                UUID.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof Map) {
            return (Map<String, Object>) event.payload();
        }
        return objectMapper.convertValue(event.payload(), new TypeReference<>() {});
    }

    @Bean
    EventHandler slaAtRiskNotificationHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_AT_RISK; }
            @Override public void handle(DomainEvent event) throws Exception { consumeAtRisk(event); }
        };
    }

    @Bean
    EventHandler slaBreachNotificationHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_BREACH; }
            @Override public void handle(DomainEvent event) throws Exception { consumeBreach(event); }
        };
    }
}

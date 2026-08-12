package com.fieldservice.notification.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbox consumer for {@code CertificationExpiringAlert} and {@code CertificationExpiredAlert}
 * events published by the certification expiry sweep.
 *
 * <p>Idempotent on {@code event_id} via {@link ConsumerIdempotencyGuard}. Resolves recipients
 * per US-015: the Operations Manager (all MANAGER-role users), and the affected technician.
 * Per-recipient delivery failures are logged and do not abort the consumer.
 *
 * <p>Degradation: {@link NotificationPort#send} already wraps the external provider with a
 * circuit breaker and falls back to in-app persistence; this consumer requires only a
 * non-PERMANENT_FAILURE outcome to consider delivery successful.
 */
@Profile("worker")
@Configuration
class CertificationAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(CertificationAlertConsumer.class);

    static final String CONSUMER_NAME      = "CertificationAlertConsumer";
    static final String EVENT_TYPE_EXPIRING = "CertificationExpiringAlert";
    static final String EVENT_TYPE_EXPIRED  = "CertificationExpiredAlert";

    private final NotificationPort           notificationPort;
    private final TechnicianRepository       technicianRepository;
    private final AppUserRepository          appUserRepository;
    private final RoleAssignmentRepository   roleAssignmentRepository;
    private final ConsumerIdempotencyGuard   idempotencyGuard;
    private final ObjectMapper               objectMapper;
    private final JdbcTemplate               jdbc;
    private final Counter                    degradedCounter;

    CertificationAlertConsumer(NotificationPort notificationPort,
                                TechnicianRepository technicianRepository,
                                AppUserRepository appUserRepository,
                                RoleAssignmentRepository roleAssignmentRepository,
                                ConsumerIdempotencyGuard idempotencyGuard,
                                ObjectMapper objectMapper,
                                JdbcTemplate jdbc,
                                MeterRegistry meterRegistry) {
        this.notificationPort        = notificationPort;
        this.technicianRepository    = technicianRepository;
        this.appUserRepository       = appUserRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.idempotencyGuard        = idempotencyGuard;
        this.objectMapper            = objectMapper;
        this.jdbc                    = jdbc;
        this.degradedCounter         = Counter.builder("cert_alert_provider_degraded_total")
                .description("Certification alert notifications that fell back to in-app delivery")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consume(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_NAME)) {
            log.debug("cert_alert_consumer_idempotent_skip event_id={}", event.eventId());
            return;
        }

        Map<String, Object> payload = parsePayload(event);
        UUID technicianId      = UUID.fromString((String) payload.get("technicianId"));
        String alertStage      = (String) payload.get("alertStage");
        String expiresOn       = (String) payload.get("expiresOn");
        Number daysRem         = (Number) payload.get("daysRemaining");
        long daysRemaining     = daysRem != null ? daysRem.longValue() : 0L;

        boolean isExpired      = EVENT_TYPE_EXPIRED.equals(event.eventType());

        List<UUID> recipients  = resolveRecipients(technicianId, event.eventId());

        for (UUID recipientUserId : recipients) {
            try {
                deliverToRecipient(event.eventId(), recipientUserId, technicianId,
                        alertStage, expiresOn, daysRemaining, isExpired);
            } catch (Exception ex) {
                log.warn("cert_alert_delivery_failed event_id={} recipient_id={} stage={}",
                        event.eventId(), recipientUserId, alertStage, ex);
            }
        }
    }

    private void deliverToRecipient(UUID eventId, UUID recipientUserId, UUID technicianId,
                                     String stage, String expiresOn, long daysRemaining,
                                     boolean isExpired) {
        String contact = resolveContact(recipientUserId);
        String subject = buildSubject(stage, expiresOn, daysRemaining, isExpired);
        String body    = buildBody(stage, expiresOn, daysRemaining, isExpired, technicianId);

        NotificationRequest req = new NotificationRequest(
                eventId,
                NotificationChannel.IN_APP,
                recipientUserId,
                contact,
                subject,
                body,
                "CERTIFICATION_ALERT",
                isExpired ? "HIGH" : (daysRemaining <= 7 ? "HIGH" : "MEDIUM"));

        DeliveryOutcome outcome = notificationPort.send(req);

        if (outcome == DeliveryOutcome.DEGRADED) {
            degradedCounter.increment();
            log.warn("cert_alert_provider_degraded event_id={} recipient={}", eventId, recipientUserId);
        } else if (outcome == DeliveryOutcome.PERMANENT_FAILURE) {
            log.error("cert_alert_permanent_failure event_id={} recipient={}", eventId, recipientUserId);
        }
    }

    private List<UUID> resolveRecipients(UUID technicianId, UUID eventId) {
        List<UUID> recipients = new ArrayList<>();

        // Affected technician's own user account
        try {
            technicianRepository.findById(technicianId).ifPresentOrElse(
                    tech -> recipients.add(tech.getUserId()),
                    () -> log.warn("cert_alert_no_technician event_id={} technician_id={}",
                            eventId, technicianId));
        } catch (Exception ex) {
            log.warn("cert_alert_technician_resolve_failed event_id={} technician_id={}",
                    eventId, technicianId, ex);
        }

        // All Operations Manager (MANAGER role) users
        try {
            List<UUID> managerUserIds = resolveManagerUserIds();
            recipients.addAll(managerUserIds);
        } catch (Exception ex) {
            log.warn("cert_alert_manager_resolve_failed event_id={}", eventId, ex);
        }

        return recipients;
    }

    /**
     * Resolves user IDs for all active users with the MANAGER role.
     * Uses a native query to avoid loading every role_assignment row.
     */
    private List<UUID> resolveManagerUserIds() {
        return jdbc.queryForList(
                "SELECT ra.user_id FROM role_assignment ra " +
                "JOIN app_user u ON u.id = ra.user_id " +
                "WHERE ra.role_name = 'MANAGER' AND u.active = TRUE",
                UUID.class);
    }

    private String resolveContact(UUID userId) {
        return appUserRepository.findById(userId)
                .map(AppUser::getEmail)
                .orElse("");
    }

    private static String buildSubject(String stage, String expiresOn, long daysRemaining,
                                        boolean isExpired) {
        if (isExpired) {
            return "Certification expired on " + expiresOn;
        }
        return switch (stage) {
            case "URGENT"  -> "Certification expires in " + daysRemaining + " day(s) — urgent";
            default        -> "Certification expiring soon (" + daysRemaining + " day(s) remaining)";
        };
    }

    private static String buildBody(String stage, String expiresOn, long daysRemaining,
                                     boolean isExpired, UUID technicianId) {
        if (isExpired) {
            return "A certification for technician [" + technicianId + "] expired on "
                    + expiresOn + ". Please arrange renewal to maintain eligibility.";
        }
        return "A certification for technician [" + technicianId + "] expires on "
                + expiresOn + " (" + daysRemaining + " day(s) remaining). "
                + ("URGENT".equals(stage) ? "Urgent renewal required." : "Please arrange renewal.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof Map) {
            return (Map<String, Object>) event.payload();
        }
        return objectMapper.convertValue(event.payload(),
                new TypeReference<Map<String, Object>>() {});
    }

    // ---- EventHandler bean registration ------------------------------------

    @Bean
    EventHandler certificationExpiringAlertHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_TYPE_EXPIRING; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }

    @Bean
    EventHandler certificationExpiredAlertHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_TYPE_EXPIRED; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }
}

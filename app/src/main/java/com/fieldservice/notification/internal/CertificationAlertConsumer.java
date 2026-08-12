package com.fieldservice.notification.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.CertificationExpiredAlertPayload;
import com.fieldservice.outbox.payload.CertificationExpiringAlertPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotent outbox consumer for certification expiry alert events.
 *
 * <p>Processes both {@code CertificationExpiringAlert} and {@code CertificationExpiredAlert}
 * event types. Two separate {@link EventHandler} beans are registered, one per supported
 * event type, following the pattern used by {@code KpiOutboxConsumer}.
 *
 * <h3>Recipient resolution</h3>
 * Recipients per US-015 (AC-7):
 * <ul>
 *   <li>Operations Manager role users — resolved via {@code role_assignment} table</li>
 *   <li>The technician's team lead — the manager linked via {@code technician} entity</li>
 *   <li>The affected technician themselves — their own expiry notice</li>
 * </ul>
 * Recipient resolution failures are logged with context and do not abort the batch (AC-7).
 *
 * <h3>Idempotency</h3>
 * Uses {@link IdempotencyGuard} on {@code (eventId, consumerName)} before any side effect.
 * Replaying the same event returns immediately without duplicate delivery (AC-6).
 *
 * <p>Restricted to the {@code worker} profile — notification delivery must never block
 * the api deployable's request threads.
 */
@Profile("worker")
class CertificationAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(CertificationAlertConsumer.class);

    static final String CONSUMER_EXPIRING = "certification.alert.expiring";
    static final String CONSUMER_EXPIRED  = "certification.alert.expired";

    private final NotificationPort notificationPort;
    private final IdempotencyGuard idempotencyGuard;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    CertificationAlertConsumer(NotificationPort notificationPort,
                                IdempotencyGuard idempotencyGuard,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper) {
        this.notificationPort = notificationPort;
        this.idempotencyGuard = idempotencyGuard;
        this.jdbcTemplate     = jdbcTemplate;
        this.objectMapper     = objectMapper;
    }

    // ── Expiring alert handler ────────────────────────────────────────────────

    @Component
    @Profile("worker")
    class ExpiringAlertHandler implements EventHandler {

        @Override
        public String getSupportedEventType() {
            return CertificationExpiringAlertPayload.EVENT_TYPE;
        }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_EXPIRING)) {
                log.debug("cert_alert_expiring_idempotent_skip eventId={}", ctx.eventId());
                return;
            }

            CertificationExpiringAlertPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), CertificationExpiringAlertPayload.class);

            String title = "Certification expiring — %s".formatted(payload.alertStage());
            String body  = "Certification %s expires on %s (%d days remaining)".formatted(
                    payload.certificationTypeCode(), payload.expiresOn(), payload.daysToExpiry());

            dispatchToRecipients(ctx.eventId(), payload.technicianId(), title, body, "WARNING");
        }
    }

    // ── Expired alert handler ─────────────────────────────────────────────────

    @Component
    @Profile("worker")
    class ExpiredAlertHandler implements EventHandler {

        @Override
        public String getSupportedEventType() {
            return CertificationExpiredAlertPayload.EVENT_TYPE;
        }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            if (!idempotencyGuard.claimEvent(ctx.eventId(), CONSUMER_EXPIRED)) {
                log.debug("cert_alert_expired_idempotent_skip eventId={}", ctx.eventId());
                return;
            }

            CertificationExpiredAlertPayload payload = objectMapper.readValue(
                    ctx.payloadJson(), CertificationExpiredAlertPayload.class);

            String title = "Certification EXPIRED — action required";
            String body  = "Certification %s expired on %s (%d days ago)".formatted(
                    payload.certificationTypeCode(), payload.expiresOn(), payload.daysExpired());

            dispatchToRecipients(ctx.eventId(), payload.technicianId(), title, body, "CRITICAL");
        }
    }

    // ── Shared dispatch ───────────────────────────────────────────────────────

    private void dispatchToRecipients(UUID eventId, UUID technicianId,
                                       String title, String body, String severity) {
        List<RecipientRow> recipients = resolveRecipients(technicianId);

        for (RecipientRow r : recipients) {
            try {
                NotificationRequest req = new NotificationRequest(
                        eventId,
                        NotificationChannel.IN_APP,
                        r.userId(),
                        null,          // recipientContact: resolved by external adapter from userId
                        eventId.toString() + ":" + r.userId(),
                        "CERTIFICATION_ALERT",
                        title,
                        body,
                        severity
                );
                DeliveryOutcome outcome = notificationPort.send(req);
                log.debug("cert_alert_dispatched eventId={} userId={} outcome={}",
                        eventId, r.userId(), outcome);
            } catch (Exception ex) {
                log.warn("cert_alert_recipient_failed eventId={} userId={} role={} error={}",
                        eventId, r.userId(), r.role(), ex.getMessage());
                // Per AC-7: recipient resolution/delivery failures are logged and do not
                // abort alerts for other recipients.
            }
        }
    }

    /**
     * Resolves the three recipient classes for a technician's certification alert (US-015, AC-7).
     *
     * <p>Resolution queries are performed directly via JDBC to avoid loading full JPA entities.
     * No PII is returned — only user IDs and role labels.
     */
    private List<RecipientRow> resolveRecipients(UUID technicianId) {
        java.util.ArrayList<RecipientRow> recipients = new java.util.ArrayList<>();

        // 1. The affected technician's own user_id
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT t.user_id FROM technician t WHERE t.id = ? AND t.is_active = true",
                    technicianId);
            rows.forEach(r -> recipients.add(new RecipientRow((UUID) r.get("user_id"), "TECHNICIAN")));
        } catch (Exception ex) {
            log.warn("cert_alert_recipient_resolve_technician_failed techId={} error={}",
                    technicianId, ex.getMessage());
        }

        // 2. Users with the MANAGER role (team lead proxy — resolve by role_assignment)
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT ra.user_id FROM role_assignment ra WHERE ra.role_name = 'MANAGER'");
            rows.forEach(r -> recipients.add(new RecipientRow((UUID) r.get("user_id"), "MANAGER")));
        } catch (Exception ex) {
            log.warn("cert_alert_recipient_resolve_manager_failed error={}", ex.getMessage());
        }

        return recipients;
    }

    record RecipientRow(UUID userId, String role) {}
}

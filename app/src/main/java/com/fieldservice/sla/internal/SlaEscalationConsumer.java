package com.fieldservice.sla.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.RecipientMask;
import com.fieldservice.platform.api.DomainEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Idempotent outbox consumer for SLA escalation notifications.
 *
 * <p>Consumes {@code SlaRiskFlagged} and {@code SlaBreached} events, resolves the
 * data-driven escalation policy, finds recipients by role, applies deduplication
 * and quiet-hours suppression (breach events bypass both), and delivers through
 * the resilient {@link NotificationPort}. Records one attempt row per
 * (event_id, recipient_user_id, channel) for auditability and idempotent replay.
 *
 * <p>Isolation contract: per-recipient failures are caught internally so one bad
 * recipient never blocks others and never rethrows to the outbox poller.
 *
 * <p>Worker-profile only — never runs on the api profile.
 */
@Component
@Profile("worker")
public class SlaEscalationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationConsumer.class);

    static final String EVENT_RISK_FLAGGED = "SlaRiskFlagged";
    static final String EVENT_BREACHED     = "SlaBreached";
    static final String CATEGORY_SLA       = "SLA_ALERT";

    private final SlaEscalationPolicyResolver    policyResolver;
    private final SlaEscalationNotificationRepository notifRepo;
    private final NotificationPort               notificationPort;
    private final JdbcTemplate                   jdbc;
    private final ObjectMapper                   objectMapper;
    private final MeterRegistry                  meterRegistry;
    private final Clock                          clock;

    public SlaEscalationConsumer(SlaEscalationPolicyResolver policyResolver,
                                  SlaEscalationNotificationRepository notifRepo,
                                  NotificationPort notificationPort,
                                  JdbcTemplate jdbc,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry,
                                  Clock clock) {
        this.policyResolver   = policyResolver;
        this.notifRepo        = notifRepo;
        this.notificationPort = notificationPort;
        this.jdbc             = jdbc;
        this.objectMapper     = objectMapper;
        this.meterRegistry    = meterRegistry;
        this.clock            = clock;
    }

    // ─── Event handlers (called by SlaEscalationConfiguration) ─────────────

    @Transactional
    public void handleRiskFlagged(DomainEvent event) {
        processEvent(event, EVENT_RISK_FLAGGED, false);
    }

    @Transactional
    public void handleBreached(DomainEvent event) {
        processEvent(event, EVENT_BREACHED, true);
    }

    // ─── Core fan-out logic ──────────────────────────────────────────────────

    private void processEvent(DomainEvent event, String eventType, boolean isBreach) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            JsonNode payload = objectMapper.readTree((String) event.payload());
            UUID workOrderId = UUID.fromString(payload.get("workOrderId").asText());
            String priority  = lookupWorkOrderPriority(workOrderId);

            var policyOpt = policyResolver.resolve(eventType, priority != null ? priority : "*");
            if (policyOpt.isEmpty()) {
                log.info("sla_escalation_no_policy eventType={} priority={}", eventType, priority);
                return;
            }

            SlaEscalationPolicyResolver.ResolvedPolicy policy = policyOpt.get();
            Instant now = clock.instant();

            for (String role : policy.recipientRoles()) {
                List<Recipient> recipients = findRecipientsByRole(role);
                if (recipients.isEmpty()) {
                    recordSkipped(event.eventId(), workOrderId, role, "EMAIL",
                            "no_recipient", "No active users with role " + role);
                    continue;
                }

                for (Recipient recipient : recipients) {
                    for (String channelStr : policy.channels()) {
                        try {
                            processRecipient(event, workOrderId, recipient, role,
                                    channelStr, policy, isBreach, now, payload);
                        } catch (Exception e) {
                            log.error("sla_escalation_recipient_error event_id={} user_id={} channel={}",
                                    event.eventId(), recipient.userId(), channelStr, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("sla_escalation_process_error event_id={} eventType={}", event.eventId(), eventType, e);
        } finally {
            sample.stop(Timer.builder("sla_escalation_latency_seconds")
                    .tag("event_type", eventType)
                    .description("Latency from event to escalation send")
                    .register(meterRegistry));
        }
    }

    private void processRecipient(DomainEvent event, UUID workOrderId, Recipient recipient,
                                   String role, String channelStr,
                                   SlaEscalationPolicyResolver.ResolvedPolicy policy,
                                   boolean isBreach, Instant now, JsonNode payload) {
        UUID eventId = event.eventId();
        UUID userId  = recipient.userId();
        String email = recipient.contact();

        // Idempotency: skip if already recorded for this (eventId, user, channel)
        if (notifRepo.findByEventIdAndRecipientUserIdAndChannel(eventId, userId, channelStr).isPresent()) {
            log.debug("sla_escalation_idempotent_skip event_id={} user_id={}", eventId, userId);
            return;
        }

        // Deduplication: suppress repeated notifications for same work order + recipient in window
        if (!isBreach && policy.dedupWindowMinutes() > 0) {
            Instant since = now.minus(Duration.ofMinutes(policy.dedupWindowMinutes()));
            if (notifRepo.existsRecentNotification(workOrderId, userId, channelStr, since)) {
                persist(eventId, workOrderId, userId, role, channelStr, 0,
                        SlaEscalationNotificationEntity.OUTCOME_SUPPRESSED,
                        "dedup_window", null, RecipientMask.mask(email));
                log.debug("sla_escalation_suppressed_dedup event_id={} user_id={}", eventId, userId);
                return;
            }
        }

        // Quiet hours check (breach events bypass)
        if (!isBreach && isQuietHours(policy, now)) {
            persist(eventId, workOrderId, userId, role, channelStr, 0,
                    SlaEscalationNotificationEntity.OUTCOME_SUPPRESSED,
                    "quiet_hours", null, RecipientMask.mask(email));
            log.debug("sla_escalation_suppressed_quiet_hours event_id={} user_id={}", eventId, userId);
            return;
        }

        // Missing contact detail check
        if (email == null || email.isBlank()) {
            persist(eventId, workOrderId, userId, role, channelStr, 0,
                    SlaEscalationNotificationEntity.OUTCOME_SKIPPED,
                    "missing_channel", null, "[empty]");
            return;
        }

        String subject = buildSubject(payload, isBreach);
        String body    = buildBody(payload, isBreach);

        NotificationChannel channel = parseChannel(channelStr);
        NotificationRequest request = new NotificationRequest(
                eventId, channel, userId, email, subject, body, CATEGORY_SLA,
                isBreach ? "CRITICAL" : "WARNING");

        DeliveryOutcome outcome;
        String providerRef = null;
        String outcomeReason = null;
        int attempts = 1;

        try {
            outcome = notificationPort.send(request);
            Counter.builder("sla_escalation_notifications_sent_total")
                    .tag("channel", channelStr)
                    .tag("event_type", isBreach ? EVENT_BREACHED : EVENT_RISK_FLAGGED)
                    .tag("outcome", outcome.name())
                    .description("SLA escalation notifications sent by channel and event type")
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            outcome = DeliveryOutcome.PERMANENT_FAILURE;
            outcomeReason = e.getClass().getSimpleName();
            log.error("sla_escalation_send_error event_id={} user_id={} channel={}",
                    eventId, userId, channelStr, e);
            Counter.builder("sla_escalation_notification_failures_total")
                    .tag("channel", channelStr)
                    .tag("reason", outcomeReason)
                    .description("SLA escalation notification failures by provider reason")
                    .register(meterRegistry)
                    .increment();
        }

        String dbOutcome = mapOutcome(outcome);
        try {
            persist(eventId, workOrderId, userId, role, channelStr, attempts,
                    dbOutcome, outcomeReason, providerRef, RecipientMask.mask(email));
        } catch (DataIntegrityViolationException e) {
            // Concurrent delivery of same event — safe to ignore
            log.debug("sla_escalation_persist_conflict event_id={} user_id={}", eventId, userId);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<Recipient> findRecipientsByRole(String role) {
        return jdbc.query(
                """
                SELECT u.id, u.email
                FROM app_user u
                JOIN role_assignment ra ON ra.user_id = u.id
                WHERE ra.role_name = ?
                  AND u.active = TRUE
                LIMIT 50
                """,
                (rs, row) -> new Recipient(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("email")),
                role);
    }

    private String lookupWorkOrderPriority(UUID workOrderId) {
        try {
            return jdbc.queryForObject(
                    "SELECT priority FROM work_order WHERE id = ?",
                    String.class, workOrderId);
        } catch (Exception e) {
            return null;
        }
    }

    private void recordSkipped(UUID eventId, UUID workOrderId, String role, String channel,
                                String reason, String detail) {
        log.info("sla_escalation_skipped eventId={} role={} reason={} detail={}",
                eventId, role, reason, detail);
        Counter.builder("sla_escalation_notifications_sent_total")
                .tag("channel", channel)
                .tag("event_type", "unknown")
                .tag("outcome", "SKIPPED")
                .register(meterRegistry)
                .increment();
    }

    private void persist(UUID eventId, UUID workOrderId, UUID recipientUserId, String recipientRole,
                          String channel, int attemptCount, String outcome, String outcomeReason,
                          String providerRef, String maskedDestination) {
        SlaEscalationNotificationEntity entity = SlaEscalationNotificationEntity.create(
                eventId, workOrderId, recipientUserId, recipientRole, channel,
                attemptCount, outcome, outcomeReason, providerRef, maskedDestination);
        notifRepo.save(entity);
    }

    private static boolean isQuietHours(SlaEscalationPolicyResolver.ResolvedPolicy policy, Instant now) {
        if (policy.quietHoursStart() == null || policy.quietHoursEnd() == null) {
            return false;
        }
        try {
            ZoneId zone    = ZoneId.of(policy.zone());
            LocalTime time = now.atZone(zone).toLocalTime();
            LocalTime start = LocalTime.parse(policy.quietHoursStart());
            LocalTime end   = LocalTime.parse(policy.quietHoursEnd());
            if (start.isBefore(end)) {
                return !time.isBefore(start) && time.isBefore(end);
            } else {
                // Window spans midnight
                return !time.isBefore(start) || time.isBefore(end);
            }
        } catch (Exception e) {
            log.warn("sla_escalation_quiet_hours_parse_error: {}", e.getMessage());
            return false;
        }
    }

    private static String buildSubject(JsonNode payload, boolean isBreach) {
        String ref = payload.path("workOrderId").asText("unknown");
        if (isBreach) {
            return "[SLA BREACH] Work order " + ref + " has breached its SLA";
        }
        int mins = payload.path("minutesRemaining").asInt(0);
        return "[SLA AT RISK] Work order " + ref + " - " + mins + " minutes remaining";
    }

    private static String buildBody(JsonNode payload, boolean isBreach) {
        StringBuilder sb = new StringBuilder();
        sb.append("Work Order ID: ").append(payload.path("workOrderId").asText("")).append("\n");
        sb.append("Priority: ").append(payload.path("priority").asText("")).append("\n");
        sb.append("State: ").append(payload.path("state").asText("")).append("\n");
        if (isBreach) {
            sb.append("Overrun Minutes: ").append(payload.path("overrunMinutes").asLong(0)).append("\n");
            sb.append("Reason Code: ").append(payload.path("reasonCode").asText("")).append("\n");
        } else {
            sb.append("Minutes Remaining: ").append(payload.path("minutesRemaining").asInt(0)).append("\n");
            sb.append("Trigger Reason: ").append(payload.path("triggerReason").asText("")).append("\n");
            sb.append("Projection Basis: ").append(payload.path("projectionBasis").asText("")).append("\n");
            sb.append("Advisory: ").append(payload.path("advisory").asBoolean(true)).append("\n");
        }
        return sb.toString();
    }

    private static NotificationChannel parseChannel(String channelStr) {
        try {
            return NotificationChannel.valueOf(channelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NotificationChannel.EMAIL; // default
        }
    }

    private static String mapOutcome(DeliveryOutcome outcome) {
        return switch (outcome) {
            case SENT              -> SlaEscalationNotificationEntity.OUTCOME_SENT;
            case DEGRADED          -> SlaEscalationNotificationEntity.OUTCOME_DEGRADED;
            case RETRYABLE_FAILURE -> SlaEscalationNotificationEntity.OUTCOME_FAILED;
            case PERMANENT_FAILURE -> SlaEscalationNotificationEntity.OUTCOME_FAILED;
        };
    }

    record Recipient(UUID userId, String contact) {}
}

package com.fieldservice.sla.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.RecipientMask;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.SlaBreachedPayload;
import com.fieldservice.outbox.payload.SlaRiskFlaggedPayload;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Worker-profile-only escalation service that routes SLA events to notification recipients.
 *
 * <p>The two inner {@code @Component} EventHandler adapters are the actual Spring beans
 * registered with the outbox router; this outer class is also a {@code @Component} so it
 * can be injected into those adapters.
 *
 * <p>Restricted to the {@code worker} Spring profile — never loads on {@code api}.</p>
 *
 * <p>Consumer escalation notifications.
 *
 * <p>For each event, the consumer:
 * <ol>
 *   <li>Claims idempotency via {@link IdempotencyGuard}.</li>
 *   <li>Resolves the escalation policy for the event type and work order priority.</li>
 *   <li>For each (recipient, channel) pair: checks deduplication window and quiet hours
 *       (breach events bypass both suppression mechanisms).</li>
 *   <li>Sends via {@link NotificationPort}, which wraps the external adapter with
 *       jittered retry and circuit-breaker degraded fallback.</li>
 *   <li>Persists one {@link SlaEscalationNotificationEntity} row per recipient+channel.</li>
 * </ol>
 *
 * <p>Exceptions per recipient are caught and logged so one bad recipient cannot block
 * others. The consumer never rethrows, so a provider outage cannot abort the outbox poll.
 */
@Component
@Profile("worker")
class SlaEscalationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationConsumer.class);
    private static final String HANDLER_RISK     = "SlaEscalation.RiskFlagged";
    private static final String HANDLER_BREACHED = "SlaEscalation.Breached";

    private final NotificationPort notificationPort;
    private final SlaEscalationPolicyResolver policyResolver;
    private final SlaEscalationNotificationRepository notificationRepository;
    private final WorkOrderRepository workOrderRepository;
    private final AppUserRepository appUserRepository;
    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyGuard idempotencyGuard;
    private final SlaEscalationMetrics metrics;
    private final SlaEscalationProperties props;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    SlaEscalationConsumer(NotificationPort notificationPort,
                           SlaEscalationPolicyResolver policyResolver,
                           SlaEscalationNotificationRepository notificationRepository,
                           WorkOrderRepository workOrderRepository,
                           AppUserRepository appUserRepository,
                           JdbcTemplate jdbcTemplate,
                           IdempotencyGuard idempotencyGuard,
                           SlaEscalationMetrics metrics,
                           SlaEscalationProperties props,
                           Clock clock,
                           ObjectMapper objectMapper) {
        this.notificationPort       = notificationPort;
        this.policyResolver         = policyResolver;
        this.notificationRepository = notificationRepository;
        this.workOrderRepository    = workOrderRepository;
        this.appUserRepository      = appUserRepository;
        this.jdbcTemplate           = jdbcTemplate;
        this.idempotencyGuard       = idempotencyGuard;
        this.metrics                = metrics;
        this.props                  = props;
        this.clock                  = clock;
        this.objectMapper           = objectMapper;
    }

    void handleRiskFlagged(EventHandlerContext ctx) {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), HANDLER_RISK)) {
            return;
        }

        SlaRiskFlaggedPayload payload;
        try {
            payload = objectMapper.readValue(ctx.payloadJson(), SlaRiskFlaggedPayload.class);
        } catch (Exception e) {
            log.error("sla.escalation.parse_error eventId={} error={}", ctx.eventId(), e.getMessage());
            return;
        }

        WorkOrder wo = workOrderRepository.findById(payload.workOrderId()).orElse(null);
        if (wo == null) {
            log.warn("sla.escalation.work_order_not_found workOrderId={}", payload.workOrderId());
            return;
        }

        String priority = wo.getPriority() != null ? wo.getPriority().name() : "MEDIUM";

        policyResolver.resolve(SlaRiskFlaggedPayload.EVENT_TYPE, priority).ifPresentOrElse(
                policy -> fanOutToRecipients(ctx.eventId(), wo, policy, SlaRiskFlaggedPayload.EVENT_TYPE,
                        buildRiskMessage(wo, payload), false, payload.raisedAt()),
                () -> log.debug("sla.escalation.no_policy eventType={} priority={}", SlaRiskFlaggedPayload.EVENT_TYPE, priority)
        );
    }

    void handleBreached(EventHandlerContext ctx) {
        if (!idempotencyGuard.claimEvent(ctx.eventId(), HANDLER_BREACHED)) {
            return;
        }

        SlaBreachedPayload payload;
        try {
            payload = objectMapper.readValue(ctx.payloadJson(), SlaBreachedPayload.class);
        } catch (Exception e) {
            log.error("sla.escalation.parse_error eventId={} error={}", ctx.eventId(), e.getMessage());
            return;
        }

        WorkOrder wo = workOrderRepository.findById(payload.workOrderId()).orElse(null);
        if (wo == null) {
            log.warn("sla.escalation.work_order_not_found workOrderId={}", payload.workOrderId());
            return;
        }

        String priority = wo.getPriority() != null ? wo.getPriority().name() : "MEDIUM";

        policyResolver.resolve(SlaBreachedPayload.EVENT_TYPE, priority).ifPresentOrElse(
                policy -> fanOutToRecipients(ctx.eventId(), wo, policy, SlaBreachedPayload.EVENT_TYPE,
                        buildBreachMessage(wo, payload), true, payload.detectedAt()),
                () -> log.debug("sla.escalation.no_policy eventType={} priority={}", SlaBreachedPayload.EVENT_TYPE, priority)
        );
    }

    // ── Fan-out logic ──────────────────────────────────────────────────────────

    private void fanOutToRecipients(UUID eventId, WorkOrder wo, SlaEscalationPolicy policy,
                                     String eventType, MessageContent message,
                                     boolean isBreach, Instant eventCommitAt) {

        List<String> channels = Arrays.asList(policy.getChannels());
        List<String> roles = Arrays.asList(policy.getRecipientRoles());

        metrics.recordLatency(eventCommitAt, eventType);

        for (String role : roles) {
            List<UUID> userIds = findUsersByRole(role);
            if (userIds.isEmpty()) {
                log.info("sla.escalation.no_recipient role={} workOrderId={}", role, wo.getId());
                metrics.recordSkipped("no_recipient");
                persistRecord(eventId, wo.getId(), null, role, "UNKNOWN", eventType, 0,
                        "SKIPPED", "no_recipient:" + role, null, "***");
                continue;
            }

            for (UUID userId : userIds) {
                for (String channel : channels) {
                    try {
                        sendToRecipient(eventId, wo, eventType, message, isBreach,
                                userId, role, channel);
                    } catch (Exception ex) {
                        log.error("sla.escalation.recipient_error eventId={} recipientId={} channel={} traceId={}",
                                eventId, userId, channel, MDC.get("traceId"), ex);
                        metrics.recordFailure("unexpected_error");
                    }
                }
            }
        }
    }

    private void sendToRecipient(UUID eventId, WorkOrder wo, String eventType,
                                  MessageContent message, boolean isBreach,
                                  UUID userId, String role, String channel) {
        // Idempotency: skip if already processed
        if (notificationRepository.existsByEventIdAndRecipientUserIdAndChannel(eventId, userId, channel)) {
            log.debug("sla.escalation.idempotent_skip eventId={} userId={} channel={}", eventId, userId, channel);
            return;
        }

        // Quiet-hours suppression (breach is exempt)
        if (!isBreach && isQuietHours(policyResolver.resolve(eventType, wo.getPriority().name()).orElse(null))) {
            log.debug("sla.escalation.quiet_hours_suppressed eventId={} userId={}", eventId, userId);
            metrics.recordSkipped("quiet_hours");
            persistRecord(eventId, wo.getId(), userId, role, channel, eventType, 0,
                    "SUPPRESSED", "quiet_hours", null, "***");
            return;
        }

        // Deduplication window suppression (breach is exempt)
        if (!isBreach) {
            Instant windowStart = clock.instant().minusSeconds(props.getSuppressionWindowSeconds());
            if (notificationRepository.existsRecentSentInWindow(wo.getId(), userId, channel, windowStart)) {
                log.debug("sla.escalation.dedup_suppressed workOrderId={} userId={} channel={}", wo.getId(), userId, channel);
                metrics.recordSkipped("dedup_window");
                persistRecord(eventId, wo.getId(), userId, role, channel, eventType, 0,
                        "SUPPRESSED", "dedup_window", null, "***");
                return;
            }
        }

        // Resolve contact
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null || !user.isActive()) {
            log.info("sla.escalation.inactive_user userId={}", userId);
            metrics.recordSkipped("inactive_user");
            persistRecord(eventId, wo.getId(), userId, role, channel, eventType, 0,
                    "SKIPPED", "inactive_user", null, "***");
            return;
        }

        String contact = resolveContact(user, channel);
        if (contact == null) {
            log.info("sla.escalation.missing_channel userId={} channel={}", userId, channel);
            metrics.recordSkipped("missing_channel");
            persistRecord(eventId, wo.getId(), userId, role, channel, eventType, 0,
                    "SKIPPED", "missing_channel", null, "***");
            return;
        }

        String masked = RecipientMask.mask(contact);

        NotificationRequest req = new NotificationRequest(
                eventId,
                NotificationChannel.valueOf(channel),
                userId,
                contact,
                eventId + ":" + userId + ":" + channel,
                "SLA_ALERT",
                message.title(),
                message.body(),
                isBreach ? "CRITICAL" : "WARNING");

        DeliveryOutcome outcome = notificationPort.send(req);

        String outcomeStr = outcome.name();
        if (outcome == DeliveryOutcome.SENT) {
            metrics.recordSent(channel, eventType);
        } else {
            metrics.recordFailure(outcome.name());
        }

        persistRecord(eventId, wo.getId(), userId, role, channel, eventType, 1,
                outcomeStr, null, null, masked);

        log.info("sla.escalation.sent eventId={} userId={} channel={} outcome={}",
                eventId, userId, channel, outcome);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<UUID> findUsersByRole(String role) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT user_id FROM role_assignment WHERE role_name = ?", UUID.class, role);
        } catch (Exception e) {
            log.error("sla.escalation.role_lookup_failed role={} error={}", role, e.getMessage());
            return List.of();
        }
    }

    private String resolveContact(AppUser user, String channel) {
        return switch (channel) {
            case "EMAIL"  -> user.getEmail();
            case "IN_APP" -> user.getId().toString();
            default       -> null;
        };
    }

    private boolean isQuietHours(SlaEscalationPolicy policy) {
        if (policy == null) return false;
        Integer start = policy.getQuietHoursStart();
        Integer end   = policy.getQuietHoursEnd();
        if (start == null || end == null) return false;

        ZoneId zone = ZoneId.of(policy.getQuietHoursZone());
        int hour = ZonedDateTime.now(clock.withZone(zone)).getHour();

        // Handles ranges spanning midnight (e.g. 22–6)
        if (start <= end) {
            return hour >= start && hour < end;
        } else {
            return hour >= start || hour < end;
        }
    }

    private void persistRecord(UUID eventId, UUID workOrderId, UUID recipientUserId,
                                String role, String channel, String eventType,
                                int attemptCount, String outcome, String outcomeReason,
                                String providerMessageId, String maskedDestination) {
        try {
            var record = SlaEscalationNotificationEntity.create(
                    eventId,
                    workOrderId,
                    recipientUserId != null ? recipientUserId : UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    role, channel, eventType,
                    attemptCount, outcome, outcomeReason, providerMessageId, maskedDestination);
            notificationRepository.save(record);
        } catch (Exception e) {
            log.error("sla.escalation.persist_failed eventId={} error={}", eventId, e.getMessage());
        }
    }

    private MessageContent buildRiskMessage(WorkOrder wo, SlaRiskFlaggedPayload payload) {
        String ref   = wo.getReference() != null ? wo.getReference() : wo.getId().toString();
        String link  = props.getDeepLinkTemplate().replace("{ref}", ref);
        String title = "SLA At Risk: " + ref;
        String body  = String.format(
                "Work order %s (%s) is at SLA risk. %s minutes remaining. Reason: %s. Projection: %s. %s",
                ref,
                wo.getPriority() != null ? wo.getPriority().name() : "UNKNOWN",
                payload.minutesRemaining() != null ? payload.minutesRemaining() : "N/A",
                truncate(payload.triggerReason(), 80),
                truncate(payload.projectionBasis(), 80),
                link);
        return new MessageContent(title, body);
    }

    private MessageContent buildBreachMessage(WorkOrder wo, SlaBreachedPayload payload) {
        String ref   = wo.getReference() != null ? wo.getReference() : wo.getId().toString();
        String link  = props.getDeepLinkTemplate().replace("{ref}", ref);
        String title = "SLA Breached: " + ref;
        String body  = String.format(
                "Work order %s (%s) has breached its SLA by %d minutes. %s",
                ref,
                wo.getPriority() != null ? wo.getPriority().name() : "UNKNOWN",
                payload.overrunMinutes(),
                link);
        return new MessageContent(title, body);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    record MessageContent(String title, String body) {}

    // ── EventHandler adapters ──────────────────────────────────────────────────

    @Component
    @Profile("worker")
    static class SlaRiskFlaggedEscalationHandler implements EventHandler {
        private final SlaEscalationConsumer consumer;
        SlaRiskFlaggedEscalationHandler(SlaEscalationConsumer consumer) { this.consumer = consumer; }

        @Override
        public String getSupportedEventType() { return SlaRiskFlaggedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.handleRiskFlagged(ctx);
        }
    }

    @Component
    @Profile("worker")
    static class SlaBreachedEscalationHandler implements EventHandler {
        private final SlaEscalationConsumer consumer;
        SlaBreachedEscalationHandler(SlaEscalationConsumer consumer) { this.consumer = consumer; }

        @Override
        public String getSupportedEventType() { return SlaBreachedPayload.EVENT_TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            consumer.handleBreached(ctx);
        }
    }
}

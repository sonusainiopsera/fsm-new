package com.fieldservice.sla.internal;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.RecipientMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled grace-period escalation checker.
 *
 * <p>Runs on the {@code worker} profile only. On each tick:
 * <ol>
 *   <li>Attempts to acquire the distributed sweep advisory lock (same connection-scope lock
 *       as the SLA sweep) so only one replica escalates per tick.</li>
 *   <li>Finds open {@link SlaRiskFlag} rows where {@code manager_escalated_at IS NULL}
 *       and the flag is older than the policy grace period.</li>
 *   <li>For each eligible flag, sends a manager notification via {@link NotificationPort}
 *       and sets {@code manager_escalated_at} to prevent duplicate sends.</li>
 * </ol>
 *
 * <p>One-shot guarantee: the {@code manager_escalated_at} column is set atomically in the
 * same transaction so a second run never re-escalates the same flag.
 */
@Component
@Profile("worker")
class SlaEscalationGracePeriodChecker {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationGracePeriodChecker.class);
    private static final long ESCALATION_LOCK_KEY = 0x534C415F455343L; // "SLA_ESC"

    private final SlaRiskFlagRepository flagRepository;
    private final SlaEscalationNotificationRepository notificationRepository;
    private final SlaEscalationPolicyResolver policyResolver;
    private final WorkOrderRepository workOrderRepository;
    private final AppUserRepository appUserRepository;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationPort notificationPort;
    private final SlaEscalationMetrics metrics;
    private final SlaEscalationProperties props;
    private final Clock clock;

    SlaEscalationGracePeriodChecker(SlaRiskFlagRepository flagRepository,
                                     SlaEscalationNotificationRepository notificationRepository,
                                     SlaEscalationPolicyResolver policyResolver,
                                     WorkOrderRepository workOrderRepository,
                                     AppUserRepository appUserRepository,
                                     JdbcTemplate jdbcTemplate,
                                     NotificationPort notificationPort,
                                     SlaEscalationMetrics metrics,
                                     SlaEscalationProperties props,
                                     Clock clock) {
        this.flagRepository         = flagRepository;
        this.notificationRepository = notificationRepository;
        this.policyResolver         = policyResolver;
        this.workOrderRepository    = workOrderRepository;
        this.appUserRepository      = appUserRepository;
        this.jdbcTemplate           = jdbcTemplate;
        this.notificationPort       = notificationPort;
        this.metrics                = metrics;
        this.props                  = props;
        this.clock                  = clock;
    }

    @Scheduled(cron = "#{@slaEscalationProperties.gracePeriodCron}")
    public void checkGracePeriodEscalations() {
        if (!props.isEnabled()) return;

        boolean acquired = tryAcquireEscalationLock();
        if (!acquired) {
            log.debug("sla.escalation.grace_period.lock_skipped");
            return;
        }

        try {
            runGracePeriodCheck();
        } catch (Exception e) {
            log.error("sla.escalation.grace_period.sweep_error error={}", e.getMessage(), e);
        } finally {
            releaseEscalationLock();
        }
    }

    @Transactional
    void runGracePeriodCheck() {
        Instant now = clock.instant();

        // Fetch all open flags without manager escalation
        List<SlaRiskFlag> openFlags = flagRepository.findOpenFlagsForGracePeriodEscalation();

        for (SlaRiskFlag flag : openFlags) {
            try {
                processFlag(flag, now);
            } catch (Exception e) {
                log.error("sla.escalation.grace_period.flag_error flagId={} error={}",
                        flag.getId(), e.getMessage());
            }
        }
    }

    private void processFlag(SlaRiskFlag flag, Instant now) {
        WorkOrder wo = workOrderRepository.findById(flag.getWorkOrderId()).orElse(null);
        if (wo == null) return;

        // Work order no longer at risk (cleared or resolved)
        if (!flag.isOpen()) return;

        String priority = wo.getPriority() != null ? wo.getPriority().name() : "MEDIUM";

        SlaEscalationPolicy policy = policyResolver
                .resolve("SlaRiskFlagged", priority)
                .orElse(null);

        if (policy == null) return;

        // Check if the grace period has elapsed
        int graceMinutes = policy.getManagerGraceMinutes();
        if (graceMinutes <= 0) return; // immediate escalation handled by consumer

        Instant graceDeadline = flag.getRaisedAt().plus(Duration.ofMinutes(graceMinutes));
        if (now.isBefore(graceDeadline)) {
            return; // grace period not yet elapsed
        }

        // Find MANAGER users and escalate exactly once
        List<UUID> managerIds = findUsersByRole("MANAGER");
        if (managerIds.isEmpty()) {
            log.info("sla.escalation.grace_period.no_managers flagId={}", flag.getId());
            return;
        }

        UUID escalationEventId = UUID.randomUUID(); // synthetic event id for grace-period escalation
        String ref   = wo.getReference() != null ? wo.getReference() : wo.getId().toString();
        String link  = props.getDeepLinkTemplate().replace("{ref}", ref);
        String title = "SLA Manager Escalation: " + ref;
        String body  = String.format(
                "Work order %s (%s) remains at SLA risk %d minutes after flagging. Immediate action required. %s",
                ref,
                priority,
                graceMinutes,
                link);

        for (UUID managerId : managerIds) {
            sendManagerEscalation(escalationEventId, wo, flag, managerId, title, body);
        }

        // Mark flag as escalated (one-shot)
        flag.markManagerEscalated(now);
        flagRepository.save(flag);

        log.info("sla.escalation.grace_period.escalated flagId={} workOrderId={} managers={}",
                flag.getId(), flag.getWorkOrderId(), managerIds.size());
    }

    private void sendManagerEscalation(UUID eventId, WorkOrder wo, SlaRiskFlag flag,
                                        UUID managerId, String title, String body) {
        AppUser user = appUserRepository.findById(managerId).orElse(null);
        if (user == null || !user.isActive()) {
            metrics.recordSkipped("inactive_manager");
            return;
        }

        String contact = user.getEmail();
        if (contact == null || contact.isBlank()) {
            metrics.recordSkipped("missing_channel");
            return;
        }

        String masked = RecipientMask.mask(contact);

        NotificationRequest req = new NotificationRequest(
                eventId,
                NotificationChannel.EMAIL,
                managerId,
                contact,
                eventId + ":" + managerId + ":EMAIL:GRACE",
                "SLA_ESCALATION",
                title,
                body,
                "HIGH");

        DeliveryOutcome outcome = notificationPort.send(req);

        if (outcome == DeliveryOutcome.SENT) {
            metrics.recordSent("EMAIL", "SlaRiskFlagged_GracePeriod");
        } else {
            metrics.recordFailure(outcome.name());
        }

        var record = SlaEscalationNotificationEntity.create(
                eventId, wo.getId(), managerId, "MANAGER", "EMAIL", "SlaRiskFlagged_GracePeriod",
                1, outcome.name(), "grace_period_escalation", null, masked);
        notificationRepository.save(record);
    }

    private List<UUID> findUsersByRole(String role) {
        return jdbcTemplate.queryForList(
                "SELECT user_id FROM role_assignment WHERE role_name = ?", UUID.class, role);
    }

    private boolean tryAcquireEscalationLock() {
        try {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, ESCALATION_LOCK_KEY);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("sla.escalation.lock_acquire_failed error={}", e.getMessage());
            return false;
        }
    }

    private void releaseEscalationLock() {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + ESCALATION_LOCK_KEY + ")");
        } catch (Exception e) {
            log.warn("sla.escalation.lock_release_failed error={}", e.getMessage());
        }
    }
}

package com.fieldservice.sla.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.RecipientMask;
import com.fieldservice.platform.util.UuidV7;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Lease-guarded scheduled check for the tiered manager escalation.
 *
 * <p>Runs every 60 seconds on the worker profile. Queries open {@code sla_risk_flag} rows
 * where the flag is still open, the grace period has expired, and the one-shot
 * {@code manager_escalated_at} column is still null. For each qualifying flag, sends
 * an escalation notification to all MANAGER-role users and sets {@code manager_escalated_at}
 * so the escalation happens exactly once.
 *
 * <p>Uses the advisory lock key {@link #ESCALATION_LOCK_KEY} (distinct from the sweep lock)
 * so manager escalation and the SLA sweep do not contend.
 */
@Component
@Profile("worker")
class SlaManagerEscalationChecker {

    private static final Logger log = LoggerFactory.getLogger(SlaManagerEscalationChecker.class);

    /** Stable 64-bit key for the manager escalation lock — must not change after deployment. */
    static final long ESCALATION_LOCK_KEY = 0x736c61657363L; // "slaesc"

    private final SlaEscalationPolicyResolver   policyResolver;
    private final SlaEscalationNotificationRepository notifRepo;
    private final NotificationPort              notificationPort;
    private final JdbcTemplate                  jdbc;
    private final TransactionTemplate           txTemplate;
    private final MeterRegistry                 meterRegistry;
    private final Clock                         clock;

    SlaManagerEscalationChecker(SlaEscalationPolicyResolver policyResolver,
                                 SlaEscalationNotificationRepository notifRepo,
                                 NotificationPort notificationPort,
                                 JdbcTemplate jdbc,
                                 TransactionTemplate txTemplate,
                                 MeterRegistry meterRegistry,
                                 Clock clock) {
        this.policyResolver   = policyResolver;
        this.notifRepo        = notifRepo;
        this.notificationPort = notificationPort;
        this.jdbc             = jdbc;
        this.txTemplate       = txTemplate;
        this.meterRegistry    = meterRegistry;
        this.clock            = clock;
    }

    @Scheduled(fixedDelayString = "${sla.escalation.manager-check-interval-ms:60000}",
               initialDelayString = "${sla.escalation.manager-check-initial-delay-ms:10000}")
    void checkGracePeriodEscalations() {
        boolean acquired = tryAcquireEscalationLock();
        if (!acquired) {
            return;
        }
        try {
            runCheck();
        } finally {
            releaseEscalationLock();
        }
    }

    private void runCheck() {
        var policyOpt = policyResolver.resolve(SlaEscalationConsumer.EVENT_RISK_FLAGGED, "*");
        if (policyOpt.isEmpty()) {
            return;
        }
        int graceMinutes = policyOpt.get().managerGraceMinutes();
        if (graceMinutes <= 0) {
            return;
        }

        Instant graceCutoff = clock.instant().minus(graceMinutes, ChronoUnit.MINUTES);

        List<OpenFlagRow> qualifyingFlags = jdbc.query(
                """
                SELECT id, work_order_id, raised_at
                FROM sla_risk_flag
                WHERE cleared_at IS NULL
                  AND manager_escalated_at IS NULL
                  AND raised_at <= ?
                ORDER BY raised_at
                LIMIT 100
                """,
                (rs, row) -> new OpenFlagRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("work_order_id")),
                        rs.getTimestamp("raised_at").toInstant()),
                java.sql.Timestamp.from(graceCutoff));

        for (OpenFlagRow flag : qualifyingFlags) {
            try {
                escalateToManagers(flag);
            } catch (Exception e) {
                log.error("sla_manager_escalation_error flagId={} workOrderId={}",
                        flag.flagId(), flag.workOrderId(), e);
            }
        }
    }

    private void escalateToManagers(OpenFlagRow flag) {
        List<SlaEscalationConsumer.Recipient> managers = jdbc.query(
                """
                SELECT u.id, u.email
                FROM app_user u
                JOIN role_assignment ra ON ra.user_id = u.id
                WHERE ra.role_name = 'MANAGER' AND u.active = TRUE
                LIMIT 20
                """,
                (rs, row) -> new SlaEscalationConsumer.Recipient(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("email")));

        if (managers.isEmpty()) {
            log.info("sla_manager_escalation_no_managers flagId={}", flag.flagId());
            return;
        }

        UUID syntheticEventId = UuidV7.generate();
        String priority = lookupPriority(flag.workOrderId());

        for (SlaEscalationConsumer.Recipient manager : managers) {
            try {
                String email = manager.contact();
                if (email == null || email.isBlank()) {
                    notifRepo.save(SlaEscalationNotificationEntity.create(
                            syntheticEventId, flag.workOrderId(), manager.userId(), "MANAGER",
                            "EMAIL", 0, SlaEscalationNotificationEntity.OUTCOME_SKIPPED,
                            "missing_channel", null, "[empty]"));
                    continue;
                }

                NotificationRequest request = new NotificationRequest(
                        syntheticEventId, NotificationChannel.EMAIL, manager.userId(), email,
                        "[SLA RISK ESCALATION] Work order " + flag.workOrderId() + " still at risk",
                        buildManagerBody(flag, priority),
                        SlaEscalationConsumer.CATEGORY_SLA, "WARNING");

                DeliveryOutcome outcome = notificationPort.send(request);
                String dbOutcome = switch (outcome) {
                    case SENT -> SlaEscalationNotificationEntity.OUTCOME_SENT;
                    case DEGRADED -> SlaEscalationNotificationEntity.OUTCOME_DEGRADED;
                    default -> SlaEscalationNotificationEntity.OUTCOME_FAILED;
                };

                notifRepo.save(SlaEscalationNotificationEntity.create(
                        syntheticEventId, flag.workOrderId(), manager.userId(), "MANAGER",
                        "EMAIL", 1, dbOutcome, null, null, RecipientMask.mask(email)));

                Counter.builder("sla_escalation_notifications_sent_total")
                        .tag("channel", "EMAIL")
                        .tag("event_type", "manager_tier")
                        .tag("outcome", dbOutcome)
                        .register(meterRegistry)
                        .increment();
            } catch (Exception e) {
                log.error("sla_manager_escalation_send_error flagId={} userId={}",
                        flag.flagId(), manager.userId(), e);
            }
        }

        // Set the one-shot marker — prevents re-escalation
        txTemplate.execute(status -> {
            jdbc.update(
                    "UPDATE sla_risk_flag SET manager_escalated_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(clock.instant()), flag.flagId());
            return null;
        });

        log.info("sla_manager_escalation_sent flagId={} workOrderId={} managers={}",
                flag.flagId(), flag.workOrderId(), managers.size());
    }

    private String lookupPriority(UUID workOrderId) {
        try {
            return jdbc.queryForObject("SELECT priority FROM work_order WHERE id = ?",
                    String.class, workOrderId);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String buildManagerBody(OpenFlagRow flag, String priority) {
        return "Work Order ID: " + flag.workOrderId() + "\n" +
               "Priority: " + priority + "\n" +
               "Flag raised at: " + flag.raisedAt() + "\n" +
               "This work order remains at risk past the configured grace period.\n" +
               "Advisory: true\n";
    }

    private boolean tryAcquireEscalationLock() {
        try {
            Boolean acquired = jdbc.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, ESCALATION_LOCK_KEY);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("sla_escalation_lock_acquire_error: {}", e.getMessage());
            return false;
        }
    }

    private void releaseEscalationLock() {
        try {
            jdbc.execute("SELECT pg_advisory_unlock(" + ESCALATION_LOCK_KEY + ")");
        } catch (Exception e) {
            log.warn("sla_escalation_lock_release_error: {}", e.getMessage());
        }
    }

    record OpenFlagRow(UUID flagId, UUID workOrderId, Instant raisedAt) {}
}

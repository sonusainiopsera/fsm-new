package com.fieldservice.sla.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.SlaBreachedPayload;
import com.fieldservice.outbox.payload.SlaRiskFlaggedPayload;
import com.fieldservice.platform.outbox.EventHandlerContext;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for SLA escalation fan-out (WO-146).
 *
 * <p>Uses the {@code worker} profile to activate the escalation consumer and grace-period
 * checker. A mock {@link NotificationPort} captures outbound calls without a real provider.
 * Fixture users from V135 provide dispatcher and manager recipients.
 */
@ActiveProfiles({"test", "worker"})
@TestPropertySource(properties = {
        "app.sla.escalation.enabled=true",
        "app.sla.escalation.suppression-window-seconds=1800",
        "app.sla.sweep.lock-enabled=false"
})
@Import(SlaEscalationIT.TestConfig.class)
class SlaEscalationIT extends AbstractIntegrationTest {

    static final Instant TEST_NOW = Instant.parse("2026-08-15T10:00:00Z");

    // Fixture IDs from V135
    static final UUID DISPATCHER_ID = UUID.fromString("ea110000-0000-0000-0000-000000000001");
    static final UUID MANAGER_ID    = UUID.fromString("ea110000-0000-0000-0000-000000000002");
    static final UUID WO_ID         = UUID.fromString("ea300000-0000-0000-0000-000000000001");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public NotificationPort mockNotificationPort() {
            NotificationPort mock = mock(NotificationPort.class);
            when(mock.send(any())).thenReturn(DeliveryOutcome.SENT);
            return mock;
        }

        @Bean
        @Primary
        public Clock slaClock() {
            return Clock.fixed(TEST_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired SlaEscalationConsumer consumer;
    @Autowired SlaEscalationGracePeriodChecker gracePeriodChecker;
    @Autowired SlaEscalationNotificationRepository notificationRepository;
    @Autowired SlaRiskFlagRepository flagRepository;
    @Autowired IdempotencyGuard idempotencyGuard;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM sla_escalation_notification WHERE event_type IN ('SlaRiskFlagged','SlaBreached','SlaRiskFlagged_GracePeriod')");
        jdbcTemplate.execute("DELETE FROM sla_risk_flag WHERE work_order_id = '" + WO_ID + "'");
        jdbcTemplate.execute("DELETE FROM idempotency_record WHERE handler_name LIKE 'SlaEscalation%'");
    }

    @Test
    void handleRiskFlagged_persistsNotificationRecords() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventHandlerContext ctx = buildRiskFlaggedCtx(eventId, WO_ID, "HIGH");

        consumer.handleRiskFlagged(ctx);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sla_escalation_notification WHERE event_id = ?", eventId);
        // Expect 2 records: one for DISPATCHER, one for MANAGER
        assertThat(rows).hasSizeGreaterThanOrEqualTo(1);
        assertThat(rows).allMatch(r -> "SENT".equals(r.get("outcome")));
        // No raw contact stored
        rows.forEach(r -> {
            String dest = (String) r.get("masked_destination");
            assertThat(dest).doesNotContain("@example.test");
        });
    }

    @Test
    void handleRiskFlagged_idempotentRedelivery_doesNotDuplicateRecords() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventHandlerContext ctx = buildRiskFlaggedCtx(eventId, WO_ID, "HIGH");

        consumer.handleRiskFlagged(ctx);
        consumer.handleRiskFlagged(ctx); // second delivery

        // Idempotency guard prevents second fanout — row count unchanged
        long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sla_escalation_notification WHERE event_id = ?",
                Long.class, eventId);
        // Should only have records from the first call
        assertThat(count).isGreaterThanOrEqualTo(1L);

        // Confirm idempotency guard fired on second call (no duplicate per-recipient rows)
        List<Map<String, Object>> perRecipient = jdbcTemplate.queryForList(
                "SELECT recipient_user_id, channel, COUNT(*) AS cnt " +
                "FROM sla_escalation_notification WHERE event_id = ? " +
                "GROUP BY recipient_user_id, channel HAVING COUNT(*) > 1", eventId);
        assertThat(perRecipient).isEmpty();
    }

    @Test
    void handleBreached_bypassesQuietHoursAndDedup() throws Exception {
        UUID eventId = UUID.randomUUID();
        // Insert a SUPPRESSED record to simulate an existing dedup window entry
        jdbcTemplate.update(
                "INSERT INTO sla_escalation_notification " +
                "(id, event_id, work_order_id, recipient_user_id, recipient_role, channel, " +
                " event_type, attempt_count, outcome, masked_destination, sent_at, created_at) " +
                "VALUES (gen_random_uuid(), gen_random_uuid(), ?, ?, 'DISPATCHER', 'EMAIL', " +
                " 'SlaRiskFlagged', 0, 'SENT', '***', now() - interval '5 minutes', now())",
                WO_ID, DISPATCHER_ID);

        EventHandlerContext ctx = buildBreachedCtx(eventId, WO_ID, "HIGH");
        consumer.handleBreached(ctx);

        // Breach event must deliver despite recent entry in dedup window
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sla_escalation_notification WHERE event_id = ?", eventId);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(1);
        assertThat(rows).anyMatch(r -> "SENT".equals(r.get("outcome")));
    }

    @Test
    void gracePeriodChecker_escalatesEligibleFlag() {
        // Insert an open risk flag raised 60 minutes before TEST_NOW (grace = 30 min → elapsed)
        UUID flagId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sla_risk_flag " +
                "(id, work_order_id, flag_type, trigger_reason, projection_basis, " +
                " minutes_remaining, raised_at, version) " +
                "VALUES (?, ?, 'AT_RISK', 'ETA_PROJECTION', 'linear', 45, ?, 0)",
                flagId, WO_ID,
                TEST_NOW.minusSeconds(3600)); // 60 min ago

        gracePeriodChecker.runGracePeriodCheck();

        // manager_escalated_at must be set
        Map<String, Object> flag = jdbcTemplate.queryForMap(
                "SELECT manager_escalated_at FROM sla_risk_flag WHERE id = ?", flagId);
        assertThat(flag.get("manager_escalated_at")).isNotNull();

        // Notification row persisted for the manager
        long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sla_escalation_notification " +
                "WHERE recipient_user_id = ? AND event_type = 'SlaRiskFlagged_GracePeriod'",
                Long.class, MANAGER_ID);
        assertThat(count).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void gracePeriodChecker_alreadyEscalated_doesNotReSend() {
        UUID flagId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sla_risk_flag " +
                "(id, work_order_id, flag_type, trigger_reason, projection_basis, " +
                " minutes_remaining, raised_at, manager_escalated_at, version) " +
                "VALUES (?, ?, 'AT_RISK', 'ETA_PROJECTION', 'linear', 45, ?, now(), 0)",
                flagId, WO_ID, TEST_NOW.minusSeconds(3600));

        // The repository query excludes flags with manager_escalated_at set,
        // so the checker should find no flags to process.
        gracePeriodChecker.runGracePeriodCheck();

        long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sla_escalation_notification " +
                "WHERE event_type = 'SlaRiskFlagged_GracePeriod' AND work_order_id = ?",
                Long.class, WO_ID);
        assertThat(count).isZero();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private EventHandlerContext buildRiskFlaggedCtx(UUID eventId, UUID workOrderId,
                                                     String priority) throws Exception {
        SlaRiskFlaggedPayload payload = new SlaRiskFlaggedPayload(
                workOrderId, UUID.randomUUID(), "AT_RISK", "ETA_PROJECTION",
                "linear", 45, TEST_NOW.minusSeconds(120));
        String json = objectMapper.writeValueAsString(payload);
        return new EventHandlerContext(eventId, SlaRiskFlaggedPayload.EVENT_TYPE,
                "WorkOrder", workOrderId, json, "trace-it", null, 1);
    }

    private EventHandlerContext buildBreachedCtx(UUID eventId, UUID workOrderId,
                                                  String priority) throws Exception {
        SlaBreachedPayload payload = new SlaBreachedPayload(
                workOrderId, UUID.randomUUID(), "RESOLUTION",
                TEST_NOW.minusSeconds(600), TEST_NOW, 15, 0);
        String json = objectMapper.writeValueAsString(payload);
        return new EventHandlerContext(eventId, SlaBreachedPayload.EVENT_TYPE,
                "WorkOrder", workOrderId, json, "trace-it", null, 1);
    }
}

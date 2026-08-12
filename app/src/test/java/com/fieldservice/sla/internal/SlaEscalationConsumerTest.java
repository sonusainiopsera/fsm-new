package com.fieldservice.sla.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.outbox.IdempotencyGuard;
import com.fieldservice.outbox.payload.SlaRiskFlaggedPayload;
import com.fieldservice.platform.outbox.EventHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaEscalationConsumerTest {

    @Mock NotificationPort notificationPort;
    @Mock SlaEscalationPolicyResolver policyResolver;
    @Mock SlaEscalationNotificationRepository notificationRepository;
    @Mock WorkOrderRepository workOrderRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock IdempotencyGuard idempotencyGuard;
    @Mock SlaEscalationMetrics metrics;

    private SlaEscalationProperties props;
    private Clock fixedClock;
    private SlaEscalationConsumer consumer;
    private ObjectMapper objectMapper;

    private static final UUID WO_ID   = UUID.randomUUID();
    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new SlaEscalationProperties();
        props.setSuppressionWindowSeconds(1800);
        props.setDeepLinkTemplate("/work-orders?ref={ref}");
        // 10:00 UTC — outside default quiet hours
        fixedClock = Clock.fixed(Instant.parse("2024-06-15T10:00:00Z"), ZoneOffset.UTC);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        consumer = new SlaEscalationConsumer(
                notificationPort, policyResolver, notificationRepository,
                workOrderRepository, appUserRepository, jdbcTemplate,
                idempotencyGuard, metrics, props, fixedClock, objectMapper);
    }

    @Test
    void handleRiskFlagged_successfulSend_persistsRecord() throws Exception {
        when(idempotencyGuard.claimEvent(EVENT_ID, "SlaEscalation.RiskFlagged")).thenReturn(true);
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy("SlaRiskFlagged", "HIGH", new String[]{"DISPATCHER"});
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("DISPATCHER")))
                .thenReturn(List.of(USER_ID));
        setupUser(USER_ID, "dispatcher@example.com");
        when(notificationRepository.existsByEventIdAndRecipientUserIdAndChannel(any(), any(), any()))
                .thenReturn(false);
        when(notificationRepository.existsRecentSentInWindow(any(), any(), any(), any()))
                .thenReturn(false);
        when(notificationPort.send(any(NotificationRequest.class))).thenReturn(DeliveryOutcome.SENT);

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationPort).send(any());
        verify(notificationRepository).save(any());
        verify(metrics).recordSent("EMAIL", "SlaRiskFlagged");
    }

    @Test
    void handleRiskFlagged_idempotentRedelivery_skipsSecondSend() throws Exception {
        // Second delivery: idempotency guard returns false
        when(idempotencyGuard.claimEvent(EVENT_ID, "SlaEscalation.RiskFlagged")).thenReturn(false);

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationPort, never()).send(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void handleRiskFlagged_dedupWindow_suppressesNotification() throws Exception {
        when(idempotencyGuard.claimEvent(any(), any())).thenReturn(true);
        setupWorkOrder(WorkOrderPriority.MEDIUM);
        setupPolicy("SlaRiskFlagged", "MEDIUM", new String[]{"DISPATCHER"});
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("DISPATCHER")))
                .thenReturn(List.of(USER_ID));
        setupUser(USER_ID, "dispatcher@example.com");
        when(notificationRepository.existsByEventIdAndRecipientUserIdAndChannel(any(), any(), any()))
                .thenReturn(false);
        // Recent notification within suppression window
        when(notificationRepository.existsRecentSentInWindow(eq(WO_ID), eq(USER_ID), eq("EMAIL"), any()))
                .thenReturn(true);

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationPort, never()).send(any());
        verify(metrics).recordSkipped("dedup_window");
    }

    @Test
    void handleRiskFlagged_quietHours_suppressesNotification() throws Exception {
        // Set clock to 23:00 UTC (within 22-6 quiet window)
        Clock nightClock = Clock.fixed(Instant.parse("2024-06-15T23:00:00Z"), ZoneOffset.UTC);
        consumer = new SlaEscalationConsumer(
                notificationPort, policyResolver, notificationRepository,
                workOrderRepository, appUserRepository, jdbcTemplate,
                idempotencyGuard, metrics, props, nightClock, objectMapper);

        when(idempotencyGuard.claimEvent(any(), any())).thenReturn(true);
        setupWorkOrder(WorkOrderPriority.MEDIUM);

        SlaEscalationPolicy quietPolicy = mockPolicy("SlaRiskFlagged", "MEDIUM",
                new String[]{"DISPATCHER"}, 22, 6);
        when(policyResolver.resolve("SlaRiskFlagged", "MEDIUM")).thenReturn(Optional.of(quietPolicy));

        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("DISPATCHER")))
                .thenReturn(List.of(USER_ID));
        setupUser(USER_ID, "dispatcher@example.com");
        when(notificationRepository.existsByEventIdAndRecipientUserIdAndChannel(any(), any(), any()))
                .thenReturn(false);

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationPort, never()).send(any());
        verify(metrics).recordSkipped("quiet_hours");
    }

    @Test
    void handleRiskFlagged_noContact_recordsMissingChannel() throws Exception {
        when(idempotencyGuard.claimEvent(any(), any())).thenReturn(true);
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy("SlaRiskFlagged", "HIGH", new String[]{"DISPATCHER"});
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("DISPATCHER")))
                .thenReturn(List.of(USER_ID));
        // User with no email
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.isActive()).thenReturn(true);
        when(user.getEmail()).thenReturn(null);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(notificationRepository.existsByEventIdAndRecipientUserIdAndChannel(any(), any(), any()))
                .thenReturn(false);

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationPort, never()).send(any());
        verify(metrics).recordSkipped("missing_channel");
    }

    @Test
    void handleRiskFlagged_noPolicy_doesNotNotify() throws Exception {
        when(idempotencyGuard.claimEvent(any(), any())).thenReturn(true);
        setupWorkOrder(WorkOrderPriority.LOW);
        when(policyResolver.resolve("SlaRiskFlagged", "LOW")).thenReturn(Optional.empty());

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationPort, never()).send(any());
    }

    @Test
    void handleRiskFlagged_degradedProvider_persistsDegradedRecord() throws Exception {
        when(idempotencyGuard.claimEvent(any(), any())).thenReturn(true);
        setupWorkOrder(WorkOrderPriority.CRITICAL);
        setupPolicy("SlaRiskFlagged", "CRITICAL", new String[]{"DISPATCHER"});
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("DISPATCHER")))
                .thenReturn(List.of(USER_ID));
        setupUser(USER_ID, "d@example.com");
        when(notificationRepository.existsByEventIdAndRecipientUserIdAndChannel(any(), any(), any()))
                .thenReturn(false);
        when(notificationRepository.existsRecentSentInWindow(any(), any(), any(), any()))
                .thenReturn(false);
        // Circuit breaker open → DEGRADED
        when(notificationPort.send(any())).thenReturn(DeliveryOutcome.DEGRADED);

        consumer.handleRiskFlagged(buildRiskFlaggedCtx());

        verify(notificationRepository).save(any());
        verify(metrics).recordFailure("DEGRADED");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void setupWorkOrder(WorkOrderPriority priority) {
        WorkOrder wo = mock(WorkOrder.class);
        when(wo.getId()).thenReturn(WO_ID);
        when(wo.getReference()).thenReturn("WO-TEST-001");
        when(wo.getPriority()).thenReturn(priority);
        when(workOrderRepository.findById(WO_ID)).thenReturn(Optional.of(wo));
    }

    private void setupPolicy(String eventType, String priority, String[] roles) {
        SlaEscalationPolicy policy = mockPolicy(eventType, priority, roles, null, null);
        when(policyResolver.resolve(eventType, priority)).thenReturn(Optional.of(policy));
    }

    private void setupUser(UUID userId, String email) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(userId);
        when(user.isActive()).thenReturn(true);
        when(user.getEmail()).thenReturn(email);
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private SlaEscalationPolicy mockPolicy(String eventType, String priority, String[] roles,
                                            Integer quietStart, Integer quietEnd) {
        SlaEscalationPolicy p = mock(SlaEscalationPolicy.class);
        when(p.getEventType()).thenReturn(eventType);
        when(p.getPriority()).thenReturn(priority);
        when(p.getRecipientRoles()).thenReturn(roles);
        when(p.getChannels()).thenReturn(new String[]{"EMAIL"});
        when(p.getManagerGraceMinutes()).thenReturn(30);
        when(p.getQuietHoursStart()).thenReturn(quietStart);
        when(p.getQuietHoursEnd()).thenReturn(quietEnd);
        when(p.getQuietHoursZone()).thenReturn("UTC");
        return p;
    }

    private EventHandlerContext buildRiskFlaggedCtx() throws Exception {
        SlaRiskFlaggedPayload payload = new SlaRiskFlaggedPayload(
                WO_ID, UUID.randomUUID(), "AT_RISK", "ETA_PROJECTION",
                "linear", 45, Instant.now());
        String json = objectMapper.writeValueAsString(payload);
        return new EventHandlerContext(EVENT_ID, SlaRiskFlaggedPayload.EVENT_TYPE,
                "WorkOrder", WO_ID, json, "trace-1", null, 1);
    }
}

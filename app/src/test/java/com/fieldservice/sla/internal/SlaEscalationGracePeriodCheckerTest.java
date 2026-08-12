package com.fieldservice.sla.internal;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationPort;
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
class SlaEscalationGracePeriodCheckerTest {

    @Mock SlaRiskFlagRepository flagRepository;
    @Mock SlaEscalationNotificationRepository notificationRepository;
    @Mock SlaEscalationPolicyResolver policyResolver;
    @Mock WorkOrderRepository workOrderRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock NotificationPort notificationPort;
    @Mock SlaEscalationMetrics metrics;

    private SlaEscalationProperties props;

    private static final UUID FLAG_ID    = UUID.randomUUID();
    private static final UUID WO_ID      = UUID.randomUUID();
    private static final UUID MANAGER_ID = UUID.randomUUID();

    // Grace period is 30 minutes; flag raised 60 minutes ago — elapsed
    private static final Instant RAISED_AT     = Instant.parse("2024-06-15T09:00:00Z");
    private static final Instant NOW_ELAPSED   = Instant.parse("2024-06-15T10:01:00Z"); // 61 min after
    private static final Instant NOW_BEFORE    = Instant.parse("2024-06-15T09:15:00Z"); // 15 min after
    private static final Instant NOW_EXACT     = Instant.parse("2024-06-15T09:30:00Z"); // exactly at

    @BeforeEach
    void setUp() {
        props = new SlaEscalationProperties();
        props.setDeepLinkTemplate("/work-orders?ref={ref}");
        props.setEnabled(true);
    }

    @Test
    void runGracePeriodCheck_graceElapsed_sendsManagerEscalation() {
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_ELAPSED);

        SlaRiskFlag flag = mockFlag(RAISED_AT);
        when(flagRepository.findOpenFlagsForGracePeriodEscalation()).thenReturn(List.of(flag));
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy(30);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("MANAGER")))
                .thenReturn(List.of(MANAGER_ID));
        setupManager(MANAGER_ID, "manager@example.com");
        when(notificationPort.send(any())).thenReturn(DeliveryOutcome.SENT);

        checker.runGracePeriodCheck();

        verify(notificationPort).send(any());
        verify(notificationRepository).save(any());
        verify(flag).markManagerEscalated(NOW_ELAPSED);
        verify(flagRepository).save(flag);
        verify(metrics).recordSent("EMAIL", "SlaRiskFlagged_GracePeriod");
    }

    @Test
    void runGracePeriodCheck_graceNotElapsed_doesNotEscalate() {
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_BEFORE);

        SlaRiskFlag flag = mockFlag(RAISED_AT);
        when(flagRepository.findOpenFlagsForGracePeriodEscalation()).thenReturn(List.of(flag));
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy(30);

        checker.runGracePeriodCheck();

        verify(notificationPort, never()).send(any());
        verify(flag, never()).markManagerEscalated(any());
    }

    @Test
    void runGracePeriodCheck_exactlyAtGraceBoundary_escalates() {
        // now == raisedAt + 30 min exactly — deadline not before now, so escalates
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_EXACT);

        SlaRiskFlag flag = mockFlag(RAISED_AT);
        when(flagRepository.findOpenFlagsForGracePeriodEscalation()).thenReturn(List.of(flag));
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy(30);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("MANAGER")))
                .thenReturn(List.of(MANAGER_ID));
        setupManager(MANAGER_ID, "manager@example.com");
        when(notificationPort.send(any())).thenReturn(DeliveryOutcome.SENT);

        checker.runGracePeriodCheck();

        verify(notificationPort).send(any());
    }

    @Test
    void runGracePeriodCheck_noManagersFound_doesNotPersistRecord() {
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_ELAPSED);

        SlaRiskFlag flag = mockFlag(RAISED_AT);
        when(flagRepository.findOpenFlagsForGracePeriodEscalation()).thenReturn(List.of(flag));
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy(30);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("MANAGER")))
                .thenReturn(List.of());

        checker.runGracePeriodCheck();

        verify(notificationPort, never()).send(any());
        verify(notificationRepository, never()).save(any());
        verify(flag, never()).markManagerEscalated(any());
    }

    @Test
    void runGracePeriodCheck_noPolicy_doesNotEscalate() {
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_ELAPSED);

        SlaRiskFlag flag = mockFlag(RAISED_AT);
        when(flagRepository.findOpenFlagsForGracePeriodEscalation()).thenReturn(List.of(flag));
        setupWorkOrder(WorkOrderPriority.HIGH);
        when(policyResolver.resolve(anyString(), anyString())).thenReturn(Optional.empty());

        checker.runGracePeriodCheck();

        verify(notificationPort, never()).send(any());
    }

    @Test
    void runGracePeriodCheck_degradedProvider_persistsDegradedRecord() {
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_ELAPSED);

        SlaRiskFlag flag = mockFlag(RAISED_AT);
        when(flagRepository.findOpenFlagsForGracePeriodEscalation()).thenReturn(List.of(flag));
        setupWorkOrder(WorkOrderPriority.HIGH);
        setupPolicy(30);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq("MANAGER")))
                .thenReturn(List.of(MANAGER_ID));
        setupManager(MANAGER_ID, "manager@example.com");
        when(notificationPort.send(any())).thenReturn(DeliveryOutcome.DEGRADED);

        checker.runGracePeriodCheck();

        verify(notificationRepository).save(any());
        verify(metrics).recordFailure("DEGRADED");
        // Flag is still marked escalated to prevent retry storms
        verify(flag).markManagerEscalated(NOW_ELAPSED);
    }

    @Test
    void checkGracePeriodEscalations_disabled_doesNothing() {
        props.setEnabled(false);
        SlaEscalationGracePeriodChecker checker = buildChecker(NOW_ELAPSED);

        checker.checkGracePeriodEscalations();

        verify(flagRepository, never()).findOpenFlagsForGracePeriodEscalation();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private SlaEscalationGracePeriodChecker buildChecker(Instant now) {
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        return new SlaEscalationGracePeriodChecker(
                flagRepository, notificationRepository, policyResolver,
                workOrderRepository, appUserRepository, jdbcTemplate,
                notificationPort, metrics, props, fixedClock);
    }

    private SlaRiskFlag mockFlag(Instant raisedAt) {
        SlaRiskFlag flag = mock(SlaRiskFlag.class);
        when(flag.getId()).thenReturn(FLAG_ID);
        when(flag.getWorkOrderId()).thenReturn(WO_ID);
        when(flag.getRaisedAt()).thenReturn(raisedAt);
        when(flag.isOpen()).thenReturn(true);
        return flag;
    }

    private void setupWorkOrder(WorkOrderPriority priority) {
        WorkOrder wo = mock(WorkOrder.class);
        when(wo.getId()).thenReturn(WO_ID);
        when(wo.getReference()).thenReturn("WO-TEST-001");
        when(wo.getPriority()).thenReturn(priority);
        when(workOrderRepository.findById(WO_ID)).thenReturn(Optional.of(wo));
    }

    private void setupPolicy(int graceMinutes) {
        SlaEscalationPolicy policy = mock(SlaEscalationPolicy.class);
        when(policy.getManagerGraceMinutes()).thenReturn(graceMinutes);
        when(policyResolver.resolve("SlaRiskFlagged", "HIGH")).thenReturn(Optional.of(policy));
    }

    private void setupManager(UUID managerId, String email) {
        AppUser user = mock(AppUser.class);
        when(user.isActive()).thenReturn(true);
        when(user.getEmail()).thenReturn(email);
        when(appUserRepository.findById(managerId)).thenReturn(Optional.of(user));
    }
}

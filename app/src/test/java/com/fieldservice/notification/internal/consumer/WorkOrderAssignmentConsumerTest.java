package com.fieldservice.notification.internal.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.TemplateRenderException;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.DeadLetterService;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkOrderAssignmentConsumer} — no Spring context.
 */
class WorkOrderAssignmentConsumerTest {

    private NotificationPort         mockPort;
    private TemplateRenderer         mockRenderer;
    private TechnicianRepository     mockTechRepo;
    private AppUserRepository        mockUserRepo;
    private ConsumerIdempotencyGuard mockGuard;
    private DeadLetterService        mockDlq;
    private WorkOrderAssignmentConsumer consumer;

    private static final UUID EVENT_ID      = UUID.randomUUID();
    private static final UUID TECHNICIAN_ID = UUID.randomUUID();
    private static final UUID USER_ID       = UUID.randomUUID();
    private static final UUID WORK_ORDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockPort     = mock(NotificationPort.class);
        mockRenderer = mock(TemplateRenderer.class);
        mockTechRepo = mock(TechnicianRepository.class);
        mockUserRepo = mock(AppUserRepository.class);
        mockGuard    = mock(ConsumerIdempotencyGuard.class);
        mockDlq      = mock(DeadLetterService.class);

        consumer = new WorkOrderAssignmentConsumer(
                mockPort, mockRenderer, mockTechRepo, mockUserRepo, mockGuard, mockDlq,
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    private DomainEvent assignedEvent() {
        return new DomainEvent(EVENT_ID, "TECHNICIAN_ASSIGNED", "WORK_ORDER", WORK_ORDER_ID,
                Instant.now(), null, null,
                Map.of("technicianId", TECHNICIAN_ID.toString(), "workOrderId", WORK_ORDER_ID.toString()));
    }

    @Test
    @DisplayName("happy path: resolves technician, renders template, sends notification")
    void happyPath_sendsNotification() throws Exception {
        when(mockGuard.claimProcessing(EVENT_ID, WorkOrderAssignmentConsumer.CONSUMER_ASSIGNED)).thenReturn(true);
        Technician tech = mock(Technician.class);
        when(tech.getUserId()).thenReturn(USER_ID);
        when(mockTechRepo.findById(TECHNICIAN_ID)).thenReturn(Optional.of(tech));
        AppUser user = mock(AppUser.class);
        when(user.getEmail()).thenReturn("tech@example.test");
        when(mockUserRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(mockRenderer.render(anyString(), any(), anyString(), any()))
                .thenReturn(new TemplateRenderer.RenderedTemplate("Assigned", "You have a new work order."));
        when(mockPort.send(any())).thenReturn(DeliveryOutcome.SENT);

        consumer.consumeAssigned(assignedEvent());

        ArgumentCaptor<com.fieldservice.notification.api.NotificationRequest> captor =
                ArgumentCaptor.forClass(com.fieldservice.notification.api.NotificationRequest.class);
        verify(mockPort).send(captor.capture());
        assertThat(captor.getValue().recipientUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().channel()).isEqualTo(NotificationChannel.IN_APP);
    }

    @Test
    @DisplayName("idempotent skip: already claimed event is not processed again")
    void idempotentSkip_notProcessed() throws Exception {
        when(mockGuard.claimProcessing(EVENT_ID, WorkOrderAssignmentConsumer.CONSUMER_ASSIGNED)).thenReturn(false);

        consumer.consumeAssigned(assignedEvent());

        verify(mockPort, never()).send(any());
        verify(mockDlq, never()).quarantine(any(), any(), any(), any(), any(int.class));
    }

    @Test
    @DisplayName("missing technicianId in payload quarantines to dead-letter")
    void missingTechnicianId_quarantines() throws Exception {
        when(mockGuard.claimProcessing(EVENT_ID, WorkOrderAssignmentConsumer.CONSUMER_ASSIGNED)).thenReturn(true);
        DomainEvent badEvent = new DomainEvent(EVENT_ID, "TECHNICIAN_ASSIGNED", "WORK_ORDER",
                WORK_ORDER_ID, Instant.now(), null, null,
                Map.of("workOrderId", WORK_ORDER_ID.toString())); // no technicianId

        consumer.consumeAssigned(badEvent);

        verify(mockDlq).quarantine(eq(EVENT_ID), anyString(), anyString(), anyString(), any(int.class));
        verify(mockPort, never()).send(any());
    }

    @Test
    @DisplayName("technician not found quarantines to dead-letter")
    void technicianNotFound_quarantines() throws Exception {
        when(mockGuard.claimProcessing(EVENT_ID, WorkOrderAssignmentConsumer.CONSUMER_ASSIGNED)).thenReturn(true);
        when(mockTechRepo.findById(TECHNICIAN_ID)).thenReturn(Optional.empty());

        consumer.consumeAssigned(assignedEvent());

        verify(mockDlq).quarantine(eq(EVENT_ID), anyString(), anyString(), anyString(), any(int.class));
        verify(mockPort, never()).send(any());
    }

    @Test
    @DisplayName("template render failure (deterministic) quarantines to dead-letter")
    void templateRenderFailure_quarantines() throws Exception {
        when(mockGuard.claimProcessing(EVENT_ID, WorkOrderAssignmentConsumer.CONSUMER_ASSIGNED)).thenReturn(true);
        Technician tech = mock(Technician.class);
        when(tech.getUserId()).thenReturn(USER_ID);
        when(mockTechRepo.findById(TECHNICIAN_ID)).thenReturn(Optional.of(tech));
        when(mockUserRepo.findById(USER_ID)).thenReturn(Optional.empty());
        when(mockRenderer.render(anyString(), any(), anyString(), any()))
                .thenThrow(new TemplateRenderException("No active template"));

        consumer.consumeAssigned(assignedEvent());

        verify(mockDlq).quarantine(eq(EVENT_ID), anyString(), anyString(), anyString(), any(int.class));
        verify(mockPort, never()).send(any());
    }
}

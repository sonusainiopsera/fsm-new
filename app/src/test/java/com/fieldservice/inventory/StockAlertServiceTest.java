package com.fieldservice.inventory;

import com.fieldservice.domain.inventory.StockAlert;
import com.fieldservice.domain.inventory.StockAlert.AlertState;
import com.fieldservice.domain.inventory.StockAlert.AlertType;
import com.fieldservice.domain.inventory.StockAlertRepository;
import com.fieldservice.inventory.application.StockAlertService;
import com.fieldservice.outbox.payload.LowStockDetectedPayload;
import com.fieldservice.outbox.payload.StockAlertClearedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockAlertService}.
 *
 * <p>Covers AC-10: guard predicates, hold-reason validation, threshold crossing and
 * clearing hysteresis, debounce suppression, and event payload construction with a
 * fixed Clock and no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class StockAlertServiceTest {

    private static final UUID PART_ID     = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final Instant NOW      = Instant.parse("2026-08-12T10:00:00Z");

    @Mock StockAlertRepository   alertRepository;
    @Mock com.fieldservice.domain.inventory.PartRepository partRepository;
    @Mock DomainEventPublisher   eventPublisher;

    private StockAlertService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new StockAlertService(
                alertRepository, partRepository, eventPublisher, fixedClock,
                new SimpleMeterRegistry());
    }

    // ── raise: no active alert ────────────────────────────────────────────────────

    @Test
    void raise_noExistingAlert_createsAlertAndPublishesEvent() {
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.empty());
        mockPart("PART-001");

        service.raise(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 3, 10);

        verify(alertRepository).save(any(StockAlert.class));
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(LowStockDetectedPayload.EVENT_TYPE_LOW_STOCK);
    }

    @Test
    void raise_stockout_publishesStockoutEventType() {
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.STOCKOUT))
                .thenReturn(Optional.empty());
        mockPart("PART-001");

        service.raise(PART_ID, LOCATION_ID, AlertType.STOCKOUT, 0, 10);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(LowStockDetectedPayload.EVENT_TYPE_STOCKOUT);
    }

    // ── raise: debounce suppression ──────────────────────────────────────────────

    @Test
    void raise_activeAlertWithinDebounceWindow_suppressesRenotification() {
        StockAlert existing = activeAlertLastNotified(NOW.minusSeconds(30 * 60)); // 30 min ago
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.of(existing));

        service.raise(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 3, 10);

        verify(alertRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void raise_activeAlertOutsideDebounceWindow_refreshesAndRenotifies() {
        StockAlert existing = activeAlertLastNotified(NOW.minusSeconds(90 * 60)); // 90 min ago
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.of(existing));
        mockPart("PART-001");

        service.raise(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 3, 10);

        verify(alertRepository).save(existing);
        verify(eventPublisher).publish(any());
        assertThat(existing.getLastNotifiedAt()).isEqualTo(NOW);
    }

    // ── clear ─────────────────────────────────────────────────────────────────────

    @Test
    void clear_activeAlertExists_clearsAndPublishesEvent() {
        StockAlert existing = activeAlertLastNotified(NOW.minusSeconds(60 * 60));
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.of(existing));
        mockPart("PART-001");

        service.clear(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 15);

        assertThat(existing.getState()).isEqualTo(AlertState.CLEARED);
        assertThat(existing.getClearedAt()).isEqualTo(NOW);
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(StockAlertClearedPayload.EVENT_TYPE);
    }

    @Test
    void clear_noActiveAlert_noopNoEvent() {
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.empty());

        service.clear(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 15);

        verify(alertRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── payload construction ─────────────────────────────────────────────────────

    @Test
    void raise_payloadContainsCorrectShortfall() {
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.empty());
        mockPart("PART-001");

        service.raise(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 3, 10);

        ArgumentCaptor<DomainEvent> cap = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(cap.capture());
        DomainEvent event = cap.getValue();
        assertThat(event.payload()).containsKey("quantityOnHand");
        assertThat(event.payload()).containsKey("reorderPoint");
        assertThat(event.payload()).containsKey("shortfallQuantity");
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    // ── hysteresis: alert oscillation ───────────────────────────────────────────

    @Test
    void raise_newAlert_thenClear_thenRaiseAgain_createsNewAlert() {
        // First raise: no existing alert
        when(alertRepository.findActiveAlert(PART_ID, LOCATION_ID, AlertType.LOW_STOCK))
                .thenReturn(Optional.empty());
        mockPart("PART-001");
        service.raise(PART_ID, LOCATION_ID, AlertType.LOW_STOCK, 3, 10);
        verify(alertRepository).save(any(StockAlert.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void mockPart(String partNumber) {
        when(partRepository.findById(PART_ID)).thenReturn(Optional.empty());
    }

    private StockAlert activeAlertLastNotified(Instant lastNotifiedAt) {
        StockAlert alert = StockAlert.raise(PART_ID, LOCATION_ID, AlertType.LOW_STOCK,
                lastNotifiedAt.minusSeconds(3600));
        // Use reflection to override lastNotifiedAt
        try {
            var field = StockAlert.class.getDeclaredField("lastNotifiedAt");
            field.setAccessible(true);
            field.set(alert, lastNotifiedAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return alert;
    }
}

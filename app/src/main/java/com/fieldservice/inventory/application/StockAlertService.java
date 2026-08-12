package com.fieldservice.inventory.application;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.StockAlert;
import com.fieldservice.domain.inventory.StockAlert.AlertState;
import com.fieldservice.domain.inventory.StockAlert.AlertType;
import com.fieldservice.domain.inventory.StockAlertRepository;
import com.fieldservice.outbox.payload.LowStockDetectedPayload;
import com.fieldservice.outbox.payload.StockAlertClearedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages stock alert lifecycle with hysteresis and debounce.
 *
 * <p>{@link #raise} checks the persisted {@code stock_alert} table for an existing ACTIVE
 * alert. Within the debounce window, re-notification is suppressed. Outside the window,
 * {@code last_notified_at} is refreshed and the event is re-emitted. A new alert always
 * publishes an event.
 *
 * <p>{@link #clear} transitions the alert to CLEARED and publishes {@code StockAlertCleared}.
 *
 * <p>Procurement is explicitly out of scope: events terminate at notification fan-out only.
 * No purchase order or supplier integration is created.
 */
@Service
@Transactional
public class StockAlertService {

    private static final Logger log = LoggerFactory.getLogger(StockAlertService.class);

    @Value("${app.inventory.alert.debounce-minutes:60}")
    private int debounceMinutes;

    private final StockAlertRepository alertRepository;
    private final PartRepository       partRepository;
    private final DomainEventPublisher eventPublisher;
    private final Clock                clock;
    private final Counter              notificationFailureCounter;

    public StockAlertService(
            StockAlertRepository alertRepository,
            PartRepository partRepository,
            DomainEventPublisher eventPublisher,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.alertRepository = alertRepository;
        this.partRepository  = partRepository;
        this.eventPublisher  = eventPublisher;
        this.clock           = clock;
        this.notificationFailureCounter = Counter.builder("inventory_stock_alert_notification_failures_total")
                .description("Number of stock alert notification publication failures")
                .register(meterRegistry);
    }

    /**
     * Raises a LOW_STOCK or STOCKOUT alert with hysteresis.
     *
     * <ul>
     *   <li>No active alert: creates one and publishes the notification event.</li>
     *   <li>Active alert within debounce window: suppresses re-notification.</li>
     *   <li>Active alert outside debounce window: refreshes {@code last_notified_at}
     *       and re-emits the event.</li>
     * </ul>
     *
     * @param partId       the part to alert on
     * @param locationId   the stock location
     * @param alertType    LOW_STOCK or STOCKOUT
     * @param qtyOnHand    current quantity on hand
     * @param reorderPoint the part's reorder point threshold
     */
    public void raise(UUID partId, UUID locationId, AlertType alertType,
                      int qtyOnHand, int reorderPoint) {
        Instant now = clock.instant();
        Optional<StockAlert> existing = alertRepository.findActiveAlert(partId, locationId, alertType);

        if (existing.isPresent()) {
            StockAlert alert = existing.get();
            long minutesSinceNotified = Duration.between(alert.getLastNotifiedAt(), now).toMinutes();
            if (minutesSinceNotified < debounceMinutes) {
                log.debug("stock_alert.debounced: partId={} locationId={} type={} since={}min",
                        partId, locationId, alertType, minutesSinceNotified);
                return;
            }
            alert.refreshLastNotified(now);
            alertRepository.save(alert);
            log.info("stock_alert.refreshed: partId={} locationId={} type={}", partId, locationId, alertType);
        } else {
            StockAlert alert = StockAlert.raise(partId, locationId, alertType, now);
            alertRepository.save(alert);
            log.info("stock_alert.raised: partId={} locationId={} type={}", partId, locationId, alertType);
        }

        publishAlertEvent(partId, locationId, alertType, qtyOnHand, reorderPoint, now);
    }

    /**
     * Clears an active alert when stock recovers above the threshold.
     * Emits {@code StockAlertCleared}. No-op if no active alert exists.
     *
     * @param partId     the part
     * @param locationId the stock location
     * @param alertType  the alert type to clear
     * @param qtyOnHand  current (recovered) quantity on hand
     */
    public void clear(UUID partId, UUID locationId, AlertType alertType, int qtyOnHand) {
        Instant now = clock.instant();
        alertRepository.findActiveAlert(partId, locationId, alertType).ifPresent(alert -> {
            alert.clear(now);
            alertRepository.save(alert);

            Part part = partRepository.findById(partId).orElse(null);
            String partNumber = part != null ? part.getPartNumber() : null;

            var payload = new StockAlertClearedPayload(
                    partId, partNumber, locationId, alertType.name(), qtyOnHand, now);
            try {
                DomainEvent event = DomainEvent.of(
                        StockAlertClearedPayload.EVENT_TYPE,
                        StockAlertClearedPayload.AGGREGATE_TYPE,
                        partId, now, null, null,
                        PiiRedactionUtility.toPayloadMap(payload));
                eventPublisher.publish(event);
                log.info("stock_alert.cleared: partId={} locationId={} type={}", partId, locationId, alertType);
            } catch (Exception ex) {
                notificationFailureCounter.increment();
                log.warn("stock_alert.clear_event_failed: partId={} locationId={} type={} error={}",
                        partId, locationId, alertType, ex.getMessage(), ex);
            }
        });
    }

    private void publishAlertEvent(UUID partId, UUID locationId, AlertType alertType,
                                   int qtyOnHand, int reorderPoint, Instant now) {
        try {
            Part part = partRepository.findById(partId).orElse(null);
            String partNumber = part != null ? part.getPartNumber() : null;
            int shortfall = Math.max(0, reorderPoint - qtyOnHand);
            String eventType = alertType == AlertType.STOCKOUT
                    ? LowStockDetectedPayload.EVENT_TYPE_STOCKOUT
                    : LowStockDetectedPayload.EVENT_TYPE_LOW_STOCK;

            var payload = new LowStockDetectedPayload(
                    partId, partNumber, locationId, qtyOnHand, reorderPoint, shortfall, now);
            DomainEvent event = DomainEvent.of(
                    eventType,
                    LowStockDetectedPayload.AGGREGATE_TYPE,
                    partId, now, null, null,
                    PiiRedactionUtility.toPayloadMap(payload));
            eventPublisher.publish(event);
        } catch (Exception ex) {
            notificationFailureCounter.increment();
            log.warn("stock_alert.event_publish_failed: partId={} locationId={} type={} error={}",
                    partId, locationId, alertType, ex.getMessage(), ex);
        }
    }
}

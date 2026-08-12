package com.fieldservice.inventory.application;

import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockAlert;
import com.fieldservice.inventory.repository.PartRepository;
import com.fieldservice.inventory.repository.StockAlertRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages stock alert lifecycle: raise, refresh (debounce), and clear.
 *
 * <p>Hysteresis: re-evaluation while an alert is already OPEN does not produce
 * a new event unless the debounce window has elapsed since {@code lastNotifiedAt}.
 *
 * <p>Clearing: when stock recovers above threshold, the OPEN alert is set to CLEARED
 * and a {@code StockAlertCleared} event is published.
 *
 * <p>Thread safety: callers are expected to be inside a transaction (REQUIRED or MANDATORY).
 * The unique partial index on {@code stock_alert} prevents concurrent duplicate inserts.
 */
@Service
public class StockAlertService {

    private static final Logger log = LoggerFactory.getLogger(StockAlertService.class);

    /** Default debounce window: re-notification suppressed within this window. */
    static final Duration DEFAULT_DEBOUNCE = Duration.ofMinutes(30);

    private final StockAlertRepository alertRepository;
    private final PartRepository       partRepository;
    private final DomainEventPublisher eventPublisher;
    private final Clock                clock;
    private final Duration             debounceWindow;

    public StockAlertService(StockAlertRepository alertRepository,
                              PartRepository partRepository,
                              DomainEventPublisher eventPublisher,
                              Clock clock) {
        this(alertRepository, partRepository, eventPublisher, clock, DEFAULT_DEBOUNCE);
    }

    /** Package-visible constructor for deterministic testing with a custom debounce. */
    StockAlertService(StockAlertRepository alertRepository,
                      PartRepository partRepository,
                      DomainEventPublisher eventPublisher,
                      Clock clock,
                      Duration debounceWindow) {
        this.alertRepository = alertRepository;
        this.partRepository  = partRepository;
        this.eventPublisher  = eventPublisher;
        this.clock           = clock;
        this.debounceWindow  = debounceWindow;
    }

    /**
     * Evaluates a single (part, location) balance and raises, refreshes, or clears
     * alerts for LOW_STOCK and STOCKOUT thresholds.
     *
     * <p>Must be called inside an active transaction so the alert row and the outbox
     * event commit or roll back together.
     *
     * @param partId        part to evaluate
     * @param locationId    stock location to evaluate
     * @param quantityOnHand current quantity on hand (≥ 0)
     * @param workOrderId   work order that triggered the evaluation (may be null)
     */
    @Transactional
    public void evaluate(UUID partId, UUID locationId, int quantityOnHand, UUID workOrderId) {
        Part part = partRepository.findById(partId).orElse(null);
        if (part == null || !Boolean.TRUE.equals(part.getActive())) {
            // Deactivated parts: clear any open alerts and stop
            clearIfOpen(partId, locationId, StockAlert.AlertType.LOW_STOCK);
            clearIfOpen(partId, locationId, StockAlert.AlertType.STOCKOUT);
            return;
        }

        int reorderPoint = part.getReorderPoint() != null ? part.getReorderPoint() : 0;
        Instant now = clock.instant();

        boolean isStockout  = quantityOnHand == 0;
        boolean isLowStock  = !isStockout && quantityOnHand <= reorderPoint && reorderPoint > 0;

        // ── STOCKOUT evaluation ──────────────────────────────────────────────
        if (isStockout) {
            raiseOrRefresh(partId, locationId, StockAlert.AlertType.STOCKOUT,
                    quantityOnHand, reorderPoint, workOrderId, now, "StockoutDetected");
            // If transitioning from LOW_STOCK → STOCKOUT, clear the LOW_STOCK alert
            clearIfOpen(partId, locationId, StockAlert.AlertType.LOW_STOCK);
        } else {
            clearIfOpen(partId, locationId, StockAlert.AlertType.STOCKOUT);
        }

        // ── LOW_STOCK evaluation ─────────────────────────────────────────────
        if (isLowStock) {
            raiseOrRefresh(partId, locationId, StockAlert.AlertType.LOW_STOCK,
                    quantityOnHand, reorderPoint, workOrderId, now, "LowStockDetected");
        } else if (!isStockout) {
            // Stock is above reorder point — clear any open LOW_STOCK alert
            clearIfOpen(partId, locationId, StockAlert.AlertType.LOW_STOCK);
        }
    }

    /**
     * Raises a REPLENISHMENT_NEEDED alert specifically from an AWAITING_PARTS hold
     * and publishes the ReplenishmentNeeded event in the same transaction.
     *
     * <p>Must be called inside the hold transition's active transaction.
     */
    @Transactional
    public void raiseReplenishmentNeeded(UUID partId, UUID locationId,
                                          int shortfallQuantity, UUID workOrderId,
                                          UUID actorId, String traceId) {
        Instant now = clock.instant();
        Part part = partRepository.findById(partId).orElse(null);
        if (part == null) {
            log.warn("replenishment_needed_part_not_found part_id={} work_order_id={}",
                    partId, workOrderId);
            return;
        }

        Optional<StockAlert> existing = alertRepository.findOpenAlert(
                partId, locationId, StockAlert.AlertType.REPLENISHMENT_NEEDED);

        if (existing.isPresent()) {
            StockAlert alert = existing.get();
            // Refresh debounce timestamp; no new event within debounce window
            if (isOutsideDebounceWindow(alert.getLastNotifiedAt(), now)) {
                alert.refreshNotified(now);
                alertRepository.save(alert);
                publishReplenishmentNeeded(part, locationId, shortfallQuantity,
                        workOrderId, actorId, traceId, now);
            }
        } else {
            StockAlert alert = StockAlert.raiseForWorkOrder(
                    partId, locationId, StockAlert.AlertType.REPLENISHMENT_NEEDED, now, workOrderId);
            alertRepository.save(alert);
            publishReplenishmentNeeded(part, locationId, shortfallQuantity,
                    workOrderId, actorId, traceId, now);
        }
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private void raiseOrRefresh(UUID partId, UUID locationId, StockAlert.AlertType alertType,
                                 int quantityOnHand, int reorderPoint,
                                 UUID workOrderId, Instant now, String eventType) {
        Part part = partRepository.findById(partId).orElse(null);
        if (part == null) return;

        Optional<StockAlert> existing = alertRepository.findOpenAlert(partId, locationId, alertType);
        int shortfall = Math.max(0, reorderPoint - quantityOnHand + 1);

        if (existing.isPresent()) {
            StockAlert alert = existing.get();
            if (isOutsideDebounceWindow(alert.getLastNotifiedAt(), now)) {
                alert.refreshNotified(now);
                alertRepository.save(alert);
                publishStockEvent(eventType, part, locationId, shortfall, workOrderId, now);
            }
            // Inside debounce window — suppress re-notification
        } else {
            StockAlert alert = StockAlert.raise(partId, locationId, alertType, now);
            if (workOrderId != null) {
                alert = StockAlert.raiseForWorkOrder(partId, locationId, alertType, now, workOrderId);
            }
            alertRepository.save(alert);
            publishStockEvent(eventType, part, locationId, shortfall, workOrderId, now);
        }
    }

    private void clearIfOpen(UUID partId, UUID locationId, StockAlert.AlertType alertType) {
        alertRepository.findOpenAlert(partId, locationId, alertType).ifPresent(alert -> {
            Part part = partRepository.findById(partId).orElse(null);
            Instant now = clock.instant();
            alert.clear(now);
            alertRepository.save(alert);
            if (part != null) {
                publishStockEvent("StockAlertCleared", part, locationId, 0, alert.getWorkOrderId(), now);
            }
        });
    }

    private boolean isOutsideDebounceWindow(Instant lastNotified, Instant now) {
        if (lastNotified == null) return true;
        return Duration.between(lastNotified, now).compareTo(debounceWindow) >= 0;
    }

    private void publishStockEvent(String eventType, Part part, UUID locationId,
                                    int shortfall, UUID workOrderId, Instant now) {
        try {
            ReplenishmentNeededPayload payload = new ReplenishmentNeededPayload(
                    part.getId(), part.getPartNumber(), locationId, shortfall, workOrderId, eventType);
            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(),
                    eventType,
                    "STOCK_BALANCE",
                    part.getId(),
                    now,
                    null,
                    null,
                    payload));
        } catch (Exception ex) {
            log.warn("stock_event_publish_failed event_type={} part_id={} location_id={}",
                    eventType, part.getId(), locationId, ex);
        }
    }

    private void publishReplenishmentNeeded(Part part, UUID locationId, int shortfall,
                                             UUID workOrderId, UUID actorId,
                                             String traceId, Instant now) {
        ReplenishmentNeededPayload payload = new ReplenishmentNeededPayload(
                part.getId(), part.getPartNumber(), locationId, shortfall, workOrderId,
                "ReplenishmentNeeded");
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "ReplenishmentNeeded",
                "STOCK_BALANCE",
                part.getId(),
                now,
                traceId,
                actorId,
                payload));
    }
}

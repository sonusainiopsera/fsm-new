package com.fieldservice.notification.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.util.UuidV7;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox consumer for inventory stock alert events.
 *
 * <p>Handles {@code LowStockDetected}, {@code StockoutDetected}, {@code ReplenishmentNeeded},
 * and {@code StockAlertCleared} events. Fans out to all DISPATCHER and ADMIN role users so
 * the right people see the inventory signal. Idempotent on {@code event_id}.
 *
 * <p>Procurement is explicitly out of scope: this consumer fans out to human recipients only.
 * No purchase order or supplier request is ever created.
 *
 * <p>Profile: worker — absent from API deployable, cannot block API request threads.
 */
@Profile("worker")
@Configuration
class InventoryAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryAlertConsumer.class);

    static final String CONSUMER_NAME               = "InventoryAlertConsumer";
    static final String EVENT_LOW_STOCK             = "LowStockDetected";
    static final String EVENT_STOCKOUT              = "StockoutDetected";
    static final String EVENT_REPLENISHMENT_NEEDED  = "ReplenishmentNeeded";
    static final String EVENT_ALERT_CLEARED         = "StockAlertCleared";

    private final NotificationPort         notificationPort;
    private final AppUserRepository        appUserRepository;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final ObjectMapper             objectMapper;
    private final JdbcTemplate             jdbc;
    private final Counter                  failureCounter;

    InventoryAlertConsumer(NotificationPort notificationPort,
                            AppUserRepository appUserRepository,
                            ConsumerIdempotencyGuard idempotencyGuard,
                            ObjectMapper objectMapper,
                            JdbcTemplate jdbc,
                            MeterRegistry meterRegistry) {
        this.notificationPort  = notificationPort;
        this.appUserRepository = appUserRepository;
        this.idempotencyGuard  = idempotencyGuard;
        this.objectMapper      = objectMapper;
        this.jdbc              = jdbc;
        this.failureCounter    = Counter.builder("inventory.notification.failures.total")
                .description("Inventory alert notifications that resulted in permanent delivery failure")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consume(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_NAME)) {
            log.debug("inventory_alert_consumer_skip event_id={} type={}",
                    event.eventId(), event.eventType());
            return;
        }

        Map<String, Object> payload = parsePayload(event);
        String partNumber    = (String) payload.getOrDefault("partNumber", "unknown");
        String alertType     = (String) payload.getOrDefault("alertType", event.eventType());
        Object shortfallRaw  = payload.get("shortfallQuantity");
        int    shortfall     = shortfallRaw != null ? ((Number) shortfallRaw).intValue() : 0;

        List<UUID> recipients = resolveRecipients(event.eventId());

        for (UUID recipientId : recipients) {
            try {
                deliverToRecipient(event.eventId(), recipientId, partNumber, alertType, shortfall,
                        event.eventType());
            } catch (Exception ex) {
                log.warn("inventory_alert_delivery_failed event_id={} recipient_id={} type={}",
                        event.eventId(), recipientId, event.eventType(), ex);
            }
        }
    }

    private void deliverToRecipient(UUID eventId, UUID recipientId, String partNumber,
                                     String alertType, int shortfall, String eventType) {
        String contact  = resolveContact(recipientId);
        String subject  = buildSubject(eventType, partNumber, shortfall);
        String body     = buildBody(eventType, partNumber, alertType, shortfall);
        String priority = EVENT_STOCKOUT.equals(eventType) ? "HIGH" : "MEDIUM";

        NotificationRequest req = new NotificationRequest(
                eventId,
                NotificationChannel.IN_APP,
                recipientId,
                contact,
                subject,
                body,
                "INVENTORY_ALERT",
                priority);

        DeliveryOutcome outcome = notificationPort.send(req);

        if (outcome == DeliveryOutcome.PERMANENT_FAILURE) {
            failureCounter.increment();
            log.error("inventory_alert_permanent_failure event_id={} recipient={}", eventId, recipientId);
        } else if (outcome == DeliveryOutcome.DEGRADED) {
            log.warn("inventory_alert_provider_degraded event_id={} recipient={}", eventId, recipientId);
        }
    }

    private List<UUID> resolveRecipients(UUID eventId) {
        List<UUID> recipients = new ArrayList<>();
        try {
            List<UUID> dispatchers = resolveUsersByRole("DISPATCHER");
            List<UUID> admins      = resolveUsersByRole("ADMIN");
            recipients.addAll(dispatchers);
            admins.stream().filter(id -> !recipients.contains(id)).forEach(recipients::add);
        } catch (Exception ex) {
            log.warn("inventory_alert_recipients_resolve_failed event_id={}", eventId, ex);
        }
        return recipients;
    }

    private List<UUID> resolveUsersByRole(String roleName) {
        return jdbc.queryForList(
                "SELECT ra.user_id FROM role_assignment ra " +
                "JOIN app_user u ON u.id = ra.user_id " +
                "WHERE ra.role_name = ? AND u.active = TRUE",
                UUID.class, roleName);
    }

    private String resolveContact(UUID userId) {
        return appUserRepository.findById(userId)
                .map(AppUser::getEmail)
                .orElse("");
    }

    private static String buildSubject(String eventType, String partNumber, int shortfall) {
        return switch (eventType) {
            case EVENT_STOCKOUT            -> "Stockout: part " + partNumber + " — zero on hand";
            case EVENT_LOW_STOCK           -> "Low stock: part " + partNumber + " (" + shortfall + " below reorder point)";
            case EVENT_REPLENISHMENT_NEEDED -> "Replenishment needed: part " + partNumber;
            case EVENT_ALERT_CLEARED       -> "Stock alert cleared: part " + partNumber;
            default                        -> "Inventory alert: part " + partNumber;
        };
    }

    private static String buildBody(String eventType, String partNumber, String alertType, int shortfall) {
        return switch (eventType) {
            case EVENT_STOCKOUT             -> "Part [" + partNumber + "] has zero units on hand. " +
                    "Replenishment required immediately to restore service capability.";
            case EVENT_LOW_STOCK            -> "Part [" + partNumber + "] is " + shortfall +
                    " unit(s) below its reorder point. Consider replenishing before stock runs out.";
            case EVENT_REPLENISHMENT_NEEDED -> "A work order placed on AWAITING_PARTS hold has identified " +
                    "a replenishment need for part [" + partNumber + "]. " +
                    "No purchase order has been created — this is a signal only.";
            case EVENT_ALERT_CLEARED        -> "The inventory alert for part [" + partNumber +
                    "] has been cleared — stock has recovered above threshold.";
            default                         -> "An inventory alert (" + alertType +
                    ") was raised for part [" + partNumber + "].";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof Map) {
            return (Map<String, Object>) event.payload();
        }
        return objectMapper.convertValue(event.payload(), new TypeReference<Map<String, Object>>() {});
    }

    @Bean
    EventHandler lowStockDetectedHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_LOW_STOCK; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }

    @Bean
    EventHandler stockoutDetectedHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_STOCKOUT; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }

    @Bean
    EventHandler replenishmentNeededHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_REPLENISHMENT_NEEDED; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }

    @Bean
    EventHandler stockAlertClearedHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_ALERT_CLEARED; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }
}

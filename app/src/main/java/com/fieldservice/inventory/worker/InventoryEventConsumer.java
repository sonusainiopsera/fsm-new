package com.fieldservice.inventory.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.ConsumerIdempotencyGuard;
import com.fieldservice.platform.outbox.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotent outbox consumer for {@code PARTS_CONSUMED} and {@code STOCK_ADJUSTED} events.
 *
 * <p>Triggers threshold evaluation for all stock balances at the affected location so that
 * a consumption that crosses the reorder point raises a notification without waiting for
 * the next scheduled sweep. Idempotent on {@code event_id} via {@link ConsumerIdempotencyGuard}.
 *
 * <p>Profile: worker — absent from API deployable, cannot block API request threads.
 */
@Profile("worker")
@Configuration
class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    static final String CONSUMER_NAME          = "InventoryEventConsumer";
    static final String EVENT_PARTS_CONSUMED   = "PARTS_CONSUMED";
    static final String EVENT_STOCK_ADJUSTED   = "STOCK_ADJUSTED";

    private final StockBalanceRepository      stockBalanceRepository;
    private final StockThresholdEvaluatorService evaluatorService;
    private final ConsumerIdempotencyGuard    idempotencyGuard;
    private final ObjectMapper                objectMapper;

    InventoryEventConsumer(StockBalanceRepository stockBalanceRepository,
                            StockThresholdEvaluatorService evaluatorService,
                            ConsumerIdempotencyGuard idempotencyGuard,
                            ObjectMapper objectMapper) {
        this.stockBalanceRepository = stockBalanceRepository;
        this.evaluatorService       = evaluatorService;
        this.idempotencyGuard       = idempotencyGuard;
        this.objectMapper           = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void consume(DomainEvent event) throws Exception {
        if (!idempotencyGuard.claimProcessing(event.eventId(), CONSUMER_NAME)) {
            log.debug("inventory_event_consumer_skip event_id={} type={}",
                    event.eventId(), event.eventType());
            return;
        }

        Map<String, Object> payload = parsePayload(event);
        Object locationRaw = payload.get("stockLocationId");
        if (locationRaw == null) {
            log.warn("inventory_event_no_location event_id={} type={}", event.eventId(), event.eventType());
            return;
        }

        UUID locationId;
        try {
            locationId = UUID.fromString(locationRaw.toString());
        } catch (IllegalArgumentException ex) {
            log.warn("inventory_event_bad_location event_id={} location={}", event.eventId(), locationRaw);
            return;
        }

        List<StockBalance> balances = stockBalanceRepository.findByLocationId(locationId);
        log.debug("inventory_event_evaluate event_id={} location_id={} balance_count={}",
                event.eventId(), locationId, balances.size());

        for (StockBalance balance : balances) {
            try {
                evaluatorService.evaluateSingle(balance);
            } catch (Exception ex) {
                log.warn("inventory_event_eval_failed event_id={} part_id={} location_id={}",
                        event.eventId(), balance.getPartId(), locationId, ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(DomainEvent event) throws Exception {
        if (event.payload() instanceof Map) {
            return (Map<String, Object>) event.payload();
        }
        return objectMapper.convertValue(event.payload(), new TypeReference<Map<String, Object>>() {});
    }

    @Bean
    EventHandler partsConsumedHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_PARTS_CONSUMED; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }

    @Bean
    EventHandler stockAdjustedHandler() {
        return new EventHandler() {
            @Override public String supportedEventType() { return EVENT_STOCK_ADJUSTED; }
            @Override public void handle(DomainEvent event) throws Exception { consume(event); }
        };
    }
}

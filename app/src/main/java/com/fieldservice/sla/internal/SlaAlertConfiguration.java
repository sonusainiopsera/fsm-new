package com.fieldservice.sla.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.EventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Registers composite outbox {@link EventHandler} beans for SLA events.
 *
 * <p>Each handler fans out to both the SSE alert stream (always active) and the
 * escalation notification consumer (worker profile only, injected as Optional).
 * Using a single composite per event type avoids the duplicate-key crash in
 * {@code OutboxPoller}'s {@code Collectors.toMap(EventHandler::supportedEventType, ...)} call.
 */
@Configuration
class SlaAlertConfiguration {

    @Bean
    EventHandler slaRiskFlaggedHandler(SlaAlertFanoutService fanout,
                                        Optional<SlaEscalationConsumer> escalation) {
        return new EventHandler() {
            @Override
            public String supportedEventType() { return "SlaRiskFlagged"; }

            @Override
            public void handle(DomainEvent event) throws Exception {
                fanout.handleRiskFlagged(event);
                if (escalation.isPresent()) {
                    escalation.get().handleRiskFlagged(event);
                }
            }
        };
    }

    @Bean
    EventHandler slaBreachedHandler(SlaAlertFanoutService fanout,
                                     Optional<SlaEscalationConsumer> escalation) {
        return new EventHandler() {
            @Override
            public String supportedEventType() { return "SlaBreached"; }

            @Override
            public void handle(DomainEvent event) throws Exception {
                fanout.handleBreached(event);
                if (escalation.isPresent()) {
                    escalation.get().handleBreached(event);
                }
            }
        };
    }
}

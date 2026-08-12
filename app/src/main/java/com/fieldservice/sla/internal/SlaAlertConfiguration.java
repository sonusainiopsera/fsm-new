package com.fieldservice.sla.internal;

import com.fieldservice.platform.outbox.EventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers outbox {@link EventHandler} beans for SLA alert fan-out.
 *
 * <p>Two handlers are registered: one for {@code SlaRiskFlagged} and one for
 * {@code SlaBreached}. The outbox poller auto-discovers them via
 * {@code List<EventHandler>} injection and routes by {@code supportedEventType()}.
 *
 * <p>Handlers run inside the outbox poller's claim transaction; throwing propagates
 * to the poller as a retryable failure with exponential backoff.
 */
@Configuration
class SlaAlertConfiguration {

    @Bean
    EventHandler slaRiskFlaggedAlertHandler(SlaAlertFanoutService fanout) {
        return new EventHandler() {
            @Override
            public String supportedEventType() { return "SlaRiskFlagged"; }

            @Override
            public void handle(com.fieldservice.platform.api.DomainEvent event) throws Exception {
                fanout.handleRiskFlagged(event);
            }
        };
    }

    @Bean
    EventHandler slaBreachedAlertHandler(SlaAlertFanoutService fanout) {
        return new EventHandler() {
            @Override
            public String supportedEventType() { return "SlaBreached"; }

            @Override
            public void handle(com.fieldservice.platform.api.DomainEvent event) throws Exception {
                fanout.handleBreached(event);
            }
        };
    }
}

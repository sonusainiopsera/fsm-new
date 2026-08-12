package com.fieldservice.portal.csat;

import com.fieldservice.platform.outbox.EventHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the CSAT issuance {@link EventHandler} bean and enables
 * {@link CsatProperties} configuration binding.
 *
 * <p>The outbox poller auto-discovers all {@code EventHandler} beans via
 * {@code List<EventHandler>} injection and routes by {@code supportedEventType()}.
 */
@Configuration
@EnableConfigurationProperties(CsatProperties.class)
class CsatIssuanceConfiguration {

    @Bean
    EventHandler csatIssuanceEventHandler(CsatIssuanceConsumer consumer) {
        return CsatIssuanceConsumer.asEventHandler(consumer);
    }
}

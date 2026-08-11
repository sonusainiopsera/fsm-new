package com.fieldservice.platform.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Registers {@link OutboxProperties} for the transactional outbox.
 * {@link JpaDomainEventPublisher} is auto-scanned as a {@code @Service}.
 * Provides a default UTC {@link Clock} bean overridable in tests.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

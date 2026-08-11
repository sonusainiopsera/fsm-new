package com.fieldservice.platform.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link OutboxProperties} for the transactional outbox.
 * {@link JpaDomainEventPublisher} is auto-scanned as a {@code @Service}.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {}

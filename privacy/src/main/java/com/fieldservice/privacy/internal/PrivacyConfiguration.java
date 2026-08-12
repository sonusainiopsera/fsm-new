package com.fieldservice.privacy.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the Spring Cache abstraction and scheduled task support for the privacy module.
 * Also registers {@link RetentionProperties} for {@code @ConfigurationProperties} binding.
 */
@Configuration
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties(RetentionProperties.class)
class PrivacyConfiguration {}

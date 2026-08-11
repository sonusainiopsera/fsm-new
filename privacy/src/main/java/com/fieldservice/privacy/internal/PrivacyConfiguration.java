package com.fieldservice.privacy.internal;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the Spring Cache abstraction for the privacy module.
 *
 * <p>Without an explicit {@link org.springframework.cache.CacheManager} bean,
 * Spring Boot auto-configures a {@code ConcurrentMapCacheManager}.
 * Production deployments may override this with a Caffeine or Redis manager
 * by declaring a {@code CacheManager} bean elsewhere.
 */
@Configuration
@EnableCaching
class PrivacyConfiguration {}

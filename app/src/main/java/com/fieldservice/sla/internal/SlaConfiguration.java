package com.fieldservice.sla.internal;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Spring configuration for the SLA module.
 *
 * <p>Registers a Caffeine cache manager for {@link SlaPolicyService#CACHE_NAME} with
 * a 60-second TTL and explicit eviction on admin write. The system clock is also
 * registered as a bean so all SLA arithmetic uses wall-clock time; tests override
 * this bean with a fixed-clock equivalent.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(SlaAlertStreamProperties.class)
public class SlaConfiguration {

    @Bean
    public Clock slaClock() {
        return Clock.systemUTC();
    }

    @Bean
    public CacheManager slaCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SlaPolicyService.CACHE_NAME);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(200));
        return manager;
    }
}

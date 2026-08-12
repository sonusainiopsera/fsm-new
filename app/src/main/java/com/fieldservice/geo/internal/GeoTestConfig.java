package com.fieldservice.geo.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers a no-op travel cache for the {@code test} profile.
 *
 * <p>Redis is excluded from test auto-configuration; this prevents the
 * {@link RedisTravelCacheGateway} from requiring a Redis connection in tests.
 */
@Configuration
@Profile("test")
class GeoTestConfig {

    @Bean
    TravelCacheGateway travelCacheGateway() {
        return new NoOpTravelCacheGateway();
    }
}

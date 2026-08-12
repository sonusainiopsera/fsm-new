package com.fieldservice.inventory.application;

import com.fieldservice.inventory.api.PartsAvailabilityResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * No-op cache gateway used when Redis is not available.
 * Always returns empty (cache miss) and discards puts silently.
 */
@Component
@ConditionalOnMissingBean(StringRedisTemplate.class)
class NoOpAvailabilityCacheGateway implements AvailabilityCacheGateway {

    @Override
    public Optional<PartsAvailabilityResult> get(String cacheKey) {
        return Optional.empty();
    }

    @Override
    public void put(String cacheKey, PartsAvailabilityResult result) {
        // no-op
    }
}

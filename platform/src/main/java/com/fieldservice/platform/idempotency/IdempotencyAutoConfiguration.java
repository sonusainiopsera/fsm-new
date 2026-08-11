package com.fieldservice.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for the idempotency layer.
 *
 * <p>Registers {@link IdempotencyFilter} at high priority (before security) so it can
 * intercept requests before the security filter chain processes them, yet still has
 * access to the authenticated principal that Spring Security populates.
 *
 * <p>The filter is registered only when both {@link IdempotencyStore} and
 * {@link IdempotencyProperties} are on the classpath — i.e., always in the api deployable.
 */
@Configuration
public class IdempotencyAutoConfiguration {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyStore store,
            IdempotencyProperties props,
            ObjectMapper objectMapper) {

        FilterRegistrationBean<IdempotencyFilter> reg =
                new FilterRegistrationBean<>(new IdempotencyFilter(store, props, objectMapper));
        reg.addUrlPatterns("/api/*");
        // Register after Spring Security (higher order number = later in chain)
        // We want to run AFTER security so SecurityContextHolder is populated
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        reg.setName("idempotencyFilter");
        return reg;
    }
}

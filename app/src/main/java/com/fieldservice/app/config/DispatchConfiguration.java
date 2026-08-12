package com.fieldservice.app.config;

import com.fieldservice.dispatch.web.RecommendationCursor;
import com.fieldservice.platform.pagination.PaginationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires dispatch-module beans that require application-level configuration. */
@Configuration
public class DispatchConfiguration {

    /**
     * Recommendation keyset cursor codec — shares the platform HMAC key so a single
     * secret is required in production.
     */
    @Bean
    public RecommendationCursor recommendationCursor(PaginationProperties props) {
        return new RecommendationCursor(props.getCursorHmacKey());
    }
}

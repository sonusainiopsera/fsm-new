package com.fieldservice.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Global Jackson ObjectMapper configuration.
 * Fails loudly on unknown properties to surface API contract violations early.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                // Reject unrecognised JSON properties instead of silently ignoring them
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Serialize dates as ISO-8601 strings, not numeric timestamps
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Enums are serialised as their name() string
                .featuresToDisable(SerializationFeature.WRITE_ENUMS_USING_INDEX);
    }
}

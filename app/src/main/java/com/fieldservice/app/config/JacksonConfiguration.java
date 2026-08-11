package com.fieldservice.app.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson deserialisation hardening.
 *
 * <ul>
 *   <li>Fail on unknown properties — rejects mass-assignment vectors (closes OWASP A03)</li>
 *   <li>Fail on null for primitives — surfaces hidden nullability bugs early</li>
 *   <li>Disable case-insensitive enum parsing — out-of-vocabulary enum strings must fail
 *       explicitly at the deserialisation layer, not silently coerce to a nearby value</li>
 * </ul>
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonHardening() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .featuresToEnable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .featuresToDisable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    }
}

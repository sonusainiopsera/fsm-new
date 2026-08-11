package com.fieldservice.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a {@link Clock} bean for injection everywhere time is used.
 * Tests override this bean with a fixed clock for deterministic assertions.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

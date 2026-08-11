package com.fieldservice.workorder.lifecycle.guards;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a UTC {@link Clock} bean for use by time-sensitive guards such as
 * {@link CertificationCurrencyGuard}. The {@code ConditionalOnMissingBean} allows
 * tests to substitute a fixed-clock bean without importing this configuration.
 */
@Configuration
public class GuardClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}

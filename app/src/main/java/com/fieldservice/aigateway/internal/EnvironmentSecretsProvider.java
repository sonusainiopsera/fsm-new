package com.fieldservice.aigateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the AI provider API key from the environment variable {@code AI_PROVIDER_API_KEY}.
 * The key is cached in an {@link AtomicReference} and replaced atomically on {@link #refresh()}.
 */
class EnvironmentSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSecretsProvider.class);
    private static final String ENV_KEY = "AI_PROVIDER_API_KEY";

    private final Environment environment;
    private final AtomicReference<String> cached = new AtomicReference<>();

    EnvironmentSecretsProvider(Environment environment) {
        this.environment = environment;
        refresh();
    }

    @Override
    public String getApiKey() {
        String key = cached.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("AI provider API key is not configured");
        }
        return key;
    }

    @Override
    public void refresh() {
        String value = environment.getProperty(ENV_KEY);
        if (value == null || value.isBlank()) {
            log.warn("AI provider credential not found in environment; variable={}", ENV_KEY);
            cached.set("");
        } else {
            cached.set(value);
            log.info("AI provider credential loaded; variable={}", ENV_KEY);
        }
    }
}

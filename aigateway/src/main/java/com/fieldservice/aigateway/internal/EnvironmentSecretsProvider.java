package com.fieldservice.aigateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads credentials from environment variables.
 *
 * <p>In production, the environment variable is injected by the container
 * runtime from the managed secrets store (e.g., AWS Secrets Manager sidecar,
 * Kubernetes ExternalSecret). No credential literal ever appears in config files.
 */
class EnvironmentSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSecretsProvider.class);

    private final String envVarName;
    private volatile String apiKey;

    EnvironmentSecretsProvider(String envVarName) {
        this.envVarName = envVarName;
        this.apiKey = loadFromEnv();
    }

    @Override
    public String getApiKey() {
        String key = apiKey;
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "AI provider credential not configured — environment variable is absent or empty");
        }
        return key;
    }

    @Override
    public void refresh() {
        String refreshed = loadFromEnv();
        this.apiKey = refreshed;
        log.info("AI provider credential refreshed from environment");
    }

    private String loadFromEnv() {
        return System.getenv(envVarName);
    }
}

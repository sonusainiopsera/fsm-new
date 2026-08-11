package com.fieldservice.aigateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the provider API key from the environment/configuration property
 * {@code AI_PROVIDER_API_KEY}. No credential literal appears in any YAML or properties file.
 *
 * <p>The key is stored in an {@link AtomicReference} so rotation events can update it
 * without restarting the container.
 */
class EnvironmentSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSecretsProvider.class);

    private final AtomicReference<String> apiKey;
    private final AiGatewayProperties properties;

    EnvironmentSecretsProvider(AiGatewayProperties properties) {
        this.properties = properties;
        this.apiKey = new AtomicReference<>(properties.provider().apiKey());
        log.info("ai_secrets_loaded key_present={}", !properties.provider().apiKey().isBlank());
    }

    @Override
    public String getApiKey() { return apiKey.get(); }

    @Override
    public void refresh() {
        String fresh = properties.provider().apiKey();
        apiKey.set(fresh);
        log.info("ai_secrets_refreshed key_present={}", !fresh.isBlank());
    }
}

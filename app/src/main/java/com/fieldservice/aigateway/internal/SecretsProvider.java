package com.fieldservice.aigateway.internal;

/**
 * Abstraction over the secrets management store. Credentials are resolved at container start
 * and refreshed on rotation without requiring a restart.
 *
 * <p>Implementations must never include the credential value in log output, exception messages,
 * or serialised responses.
 */
interface SecretsProvider {

    /**
     * Returns the current API key for the configured AI provider.
     * The returned value must not be logged or included in exception messages.
     */
    String getApiKey();

    /**
     * Re-reads the credential from the backing store. Called on rotation events.
     * In-flight requests complete on the prior credential; subsequent calls use the new one.
     */
    void refresh();
}

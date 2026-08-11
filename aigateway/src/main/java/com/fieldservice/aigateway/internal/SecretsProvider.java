package com.fieldservice.aigateway.internal;

/**
 * Abstraction over the secrets store.
 *
 * <p>Callers retrieve credentials by logical name; the concrete implementation
 * maps names to environment variables, a managed secrets service, or a vault.
 * Credentials must never appear in logs, exception messages, or configuration literals.
 */
interface SecretsProvider {

    /**
     * Returns the current API key for the AI provider.
     * Never null; throws if the credential is absent.
     */
    String getApiKey();

    /**
     * Triggers a credential refresh.
     * Called on rotation events; in-flight calls complete on the old credential.
     */
    void refresh();
}

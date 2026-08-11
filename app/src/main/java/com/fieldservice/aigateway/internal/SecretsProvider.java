package com.fieldservice.aigateway.internal;

/**
 * Abstraction over the secrets manager. Implementations resolve credentials at startup
 * and refresh on rotation events — no credential literal ever lives in configuration.
 */
interface SecretsProvider {
    String getApiKey();
    void refresh();
}

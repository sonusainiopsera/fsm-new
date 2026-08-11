package com.fieldservice.notification.internal;

/**
 * Abstraction over the notification provider credentials store.
 *
 * <p>Implementations must never log or expose the raw credential.
 */
interface NotificationProviderSecrets {

    /** Returns the bearer token for the configured notification provider. */
    String getApiKey();

    /** Re-reads credentials from the backing store. */
    void refresh();
}

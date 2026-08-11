package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationRequest;

/**
 * Contract for a pluggable outbound notification adapter.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Not store or log raw PII — use only the masked recipient token</li>
 *   <li>Throw {@link ProviderTransientException} for 429 / 5xx / timeout conditions</li>
 *   <li>Throw {@link ProviderPermanentException} for 4xx credential or validation failures</li>
 *   <li>Propagate {@link java.io.IOException} for network-level errors</li>
 * </ul>
 */
interface ExternalNotificationAdapter {

    /** Logical name used in metrics tags and attempt rows (e.g. "stub", "http"). */
    String adapterName();

    /**
     * Sends the notification via the underlying provider.
     *
     * @param request         the notification to send
     * @param maskedRecipient stable masked token (never raw PII)
     * @return provider-assigned reference ID, or {@code null} if the adapter is a stub
     * @throws ProviderTransientException for rate-limit and server errors
     * @throws ProviderPermanentException for credential/validation errors
     * @throws java.io.IOException        for network-level failures
     */
    String send(NotificationRequest request, String maskedRecipient) throws Exception;
}

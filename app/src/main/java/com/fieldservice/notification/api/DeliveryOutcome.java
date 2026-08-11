package com.fieldservice.notification.api;

/**
 * Result of a single notification send attempt.
 *
 * <ul>
 *   <li>SENT — external provider accepted the message</li>
 *   <li>DEGRADED — persisted as in-app fallback (circuit open or provider error)</li>
 *   <li>RETRYABLE_FAILURE — transient error, caller may retry later</li>
 *   <li>PERMANENT_FAILURE — unrecoverable error (bad credentials, invalid recipient)</li>
 * </ul>
 */
public enum DeliveryOutcome {
    SENT, DEGRADED, RETRYABLE_FAILURE, PERMANENT_FAILURE
}

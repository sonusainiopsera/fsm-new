package com.fieldservice.notification.api;

public enum DeliveryOutcome {
    SENT,
    DEGRADED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
}

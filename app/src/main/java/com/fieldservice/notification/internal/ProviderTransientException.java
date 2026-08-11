package com.fieldservice.notification.internal;

/** Thrown when the notification provider returns a retryable error (429, 5xx, timeout). */
class ProviderTransientException extends RuntimeException {
    ProviderTransientException(String msg) { super(msg); }
    ProviderTransientException(String msg, Throwable cause) { super(msg, cause); }
}

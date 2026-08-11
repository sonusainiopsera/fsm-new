package com.fieldservice.notification.internal;

/** Thrown by an external adapter when the provider error is transient (5xx, timeout, 429). */
class RetryableNotificationException extends RuntimeException {

    private final String failureCode;

    RetryableNotificationException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    RetryableNotificationException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    String getFailureCode() { return failureCode; }
}

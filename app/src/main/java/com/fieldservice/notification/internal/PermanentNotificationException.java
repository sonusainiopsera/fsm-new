package com.fieldservice.notification.internal;

/** Thrown by an external adapter when the provider error is permanent (4xx other than 429). */
class PermanentNotificationException extends RuntimeException {

    private final String failureCode;

    PermanentNotificationException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    String getFailureCode() { return failureCode; }
}

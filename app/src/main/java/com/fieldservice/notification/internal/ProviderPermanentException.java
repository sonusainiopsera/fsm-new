package com.fieldservice.notification.internal;

/** Thrown when the notification provider rejects the request permanently (4xx). */
class ProviderPermanentException extends RuntimeException {
    ProviderPermanentException(String msg) { super(msg); }
}

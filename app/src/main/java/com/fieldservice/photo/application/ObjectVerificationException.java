package com.fieldservice.photo.application;

/** Thrown when the HEAD-object verification fails at registration time. */
public class ObjectVerificationException extends RuntimeException {

    public ObjectVerificationException(String message) {
        super(message);
    }
}

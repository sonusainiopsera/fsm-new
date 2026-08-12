package com.fieldservice.photo.application;

/** Thrown when the presigned upload URL for a storage key has expired. */
public class IntentExpiredException extends RuntimeException {

    private final String storageKey;

    public IntentExpiredException(String storageKey) {
        super("Upload intent has expired. Request a new upload URL and retry.");
        this.storageKey = storageKey;
    }

    public String getStorageKey() { return storageKey; }
}

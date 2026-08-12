package com.fieldservice.photo.application;

/** Thrown when a registration attempt references a storage key that was never issued as an intent. */
public class IntentNotFoundException extends RuntimeException {

    private final String storageKey;

    public IntentNotFoundException(String storageKey) {
        super("No upload intent found for storage key.");
        this.storageKey = storageKey;
    }

    public String getStorageKey() { return storageKey; }
}

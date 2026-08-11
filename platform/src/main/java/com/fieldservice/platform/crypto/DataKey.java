package com.fieldservice.platform.crypto;

import java.util.Arrays;

/**
 * In-memory holder for an unwrapped (plaintext) data-encryption key.
 *
 * <p>Key material must never be logged, serialised, included in event payloads,
 * or persisted. This record exists only in heap memory and is discarded when GC'd.
 */
public final class DataKey {

    private final byte[] keyMaterial;
    private final int version;
    private final SubjectKeyState state;

    public DataKey(byte[] keyMaterial, int version, SubjectKeyState state) {
        this.keyMaterial = Arrays.copyOf(keyMaterial, keyMaterial.length);
        this.version = version;
        this.state = state;
    }

    /** Returns a defensive copy so callers cannot mutate the held key bytes. */
    public byte[] keyMaterial() {
        return Arrays.copyOf(keyMaterial, keyMaterial.length);
    }

    public int version() {
        return version;
    }

    public SubjectKeyState state() {
        return state;
    }

    /** Explicitly zeros the key bytes when no longer needed. */
    public void destroy() {
        Arrays.fill(keyMaterial, (byte) 0);
    }

    @Override
    public String toString() {
        // Never include key material in toString
        return "DataKey[version=" + version + ", state=" + state + "]";
    }
}

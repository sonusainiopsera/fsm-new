package com.fieldservice.platform.crypto;

import javax.crypto.SecretKey;

/**
 * In-memory representation of an unwrapped per-subject data key.
 *
 * <p>The {@link SecretKey} is held opaquely; it is never serialised, logged or
 * included in any event payload.  The record is ephemeral: callers must not
 * store or cache it outside of the {@link SubjectKeyCache}.
 */
public record SubjectKeySpec(SecretKey secretKey, int keyVersion) {

    public SubjectKeySpec {
        if (secretKey == null) {
            throw new IllegalArgumentException("secretKey must not be null");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion must be >= 1, got: " + keyVersion);
        }
    }
}

package com.fieldservice.platform.crypto;

/** Lifecycle state of a per-subject envelope data key. */
public enum SubjectKeyState {

    /** Key is current; all encrypt/decrypt operations proceed normally. */
    ACTIVE,

    /** Key has been superseded by a newer version; existing ciphertext still decrypts. */
    ROTATED,

    /**
     * Key has been irrevocably destroyed. Decrypt operations return
     * {@link EnvelopeEncryptedStringConverter#UNRECOVERABLE_MARKER} — they never throw.
     * This state is terminal and cannot be reversed.
     */
    DESTROYED
}

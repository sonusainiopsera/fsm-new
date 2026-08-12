package com.fieldservice.platform.crypto;

/**
 * Lifecycle state of a per-subject data key stored in {@code subject_data_key}.
 *
 * <p>State transitions are one-way: ACTIVE → ROTATED → DESTROYED.
 * DESTROYED is terminal: once set it cannot be reversed.
 */
public enum SubjectKeyState {

    /** Key is the active version for encrypt and decrypt operations. */
    ACTIVE,

    /**
     * Key has been superseded by a newer version.  Existing ciphertext produced
     * under this version can still be decrypted; new encrypt calls use the ACTIVE version.
     */
    ROTATED,

    /**
     * Key material has been irrecoverably deleted.  Decrypt attempts return
     * {@link EnvelopeEncryptedStringConverter#UNRECOVERABLE_MARKER} rather than throwing.
     * This state supports lawful erasure (WO-095).
     */
    DESTROYED
}

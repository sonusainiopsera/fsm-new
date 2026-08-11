package com.fieldservice.platform.crypto;

import java.util.UUID;

/**
 * Thread-local carrier for the current encryption subject, set by
 * {@link SubjectKeyContextListener} before JPA calls the converter.
 *
 * <p>Only used for the write path (encrypt). The decrypt path is self-contained:
 * the subject ID is embedded in the ciphertext envelope.
 */
public final class SubjectKeyContext {

    private static final ThreadLocal<EncryptionContext> CONTEXT = new ThreadLocal<>();

    private SubjectKeyContext() {}

    public static void set(String subjectType, UUID subjectId) {
        CONTEXT.set(new EncryptionContext(subjectType, subjectId));
    }

    public static EncryptionContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record EncryptionContext(String subjectType, UUID subjectId) {}
}

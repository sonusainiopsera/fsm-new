package com.fieldservice.platform.crypto;

import java.util.UUID;

/**
 * Implemented by JPA entities that hold envelope-encrypted fields.
 *
 * <p>The {@link SubjectKeyContextListener} reads these values from the entity in
 * {@code @PrePersist} / {@code @PreUpdate} callbacks and places them in
 * {@link SubjectKeyContext} so the converter can look up the correct data key.
 */
public interface SubjectKeyContextProvider {

    /** Logical subject type, e.g. {@code "TECHNICIAN"} or {@code "CUSTOMER_ACCOUNT"}. */
    String getEnvelopeSubjectType();

    /** The data subject's identifier used to look up or generate the envelope key. */
    UUID getEnvelopeSubjectId();

    /**
     * Called by {@link SubjectKeyContextListener} before each write so the entity can
     * recompute any blind-index columns from the current plaintext field values.
     *
     * <p>Default implementation is a no-op; override in entities that carry blind-index fields.
     */
    default void recomputeBlindIndices() {}
}

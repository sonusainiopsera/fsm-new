package com.fieldservice.platform.crypto;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * JPA entity listener that sets the {@link SubjectKeyContext} before each write
 * so {@link EnvelopeEncryptedStringConverter} can locate the correct data key.
 *
 * <p>Applied to entities via {@code @EntityListeners(SubjectKeyContextListener.class)}.
 * Those entities must implement {@link SubjectKeyContextProvider}.
 */
public class SubjectKeyContextListener {

    @PrePersist
    @PreUpdate
    public void beforeWrite(Object entity) {
        if (entity instanceof SubjectKeyContextProvider p) {
            SubjectKeyContext.set(p.getEnvelopeSubjectType(), p.getEnvelopeSubjectId());
            p.recomputeBlindIndices();
        }
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    @PostLoad
    public void afterOperation(Object entity) {
        if (entity instanceof SubjectKeyContextProvider) {
            SubjectKeyContext.clear();
        }
    }
}

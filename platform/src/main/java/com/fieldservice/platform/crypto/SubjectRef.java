package com.fieldservice.platform.crypto;

import java.util.UUID;

/**
 * Identifies a data subject for per-subject envelope encryption.
 *
 * <p>{@code subjectType} is a stable string constant identifying the entity type
 * (e.g. {@code "TECHNICIAN"}, {@code "PORTAL_ACCOUNT"}). {@code subjectId} is the
 * entity's primary key.
 */
public record SubjectRef(String subjectType, UUID subjectId) {

    public SubjectRef {
        if (subjectType == null || subjectType.isBlank()) {
            throw new IllegalArgumentException("subjectType must not be blank");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId must not be null");
        }
    }

    public static SubjectRef of(String subjectType, UUID subjectId) {
        return new SubjectRef(subjectType, subjectId);
    }
}

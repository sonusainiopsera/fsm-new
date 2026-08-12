package com.fieldservice.privacy.api;

import java.util.UUID;

/**
 * Immutable reference identifying a data subject.
 *
 * <p>{@code subjectType} is a stable string constant from the owning module
 * (e.g. {@code "APP_USER"}, {@code "TECHNICIAN"}, {@code "CUSTOMER_CONTACT"}).
 * It matches the {@code subject_type} column in {@code dsar_request}.
 */
public record SubjectRef(String subjectType, UUID subjectId) {}

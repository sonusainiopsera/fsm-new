package com.fieldservice.privacy.api;

import java.util.UUID;

/**
 * Identifies a data subject by type and opaque identifier.
 *
 * <p>The type string is an open vocabulary (e.g. {@code "CUSTOMER"}, {@code "TECHNICIAN"},
 * {@code "APP_USER"}) so that modules can register providers for any subject type without
 * a schema change in the privacy module.
 *
 * @param subjectType a stable string discriminator identifying the subject class
 * @param subjectId   the subject's UUID within its type namespace
 */
public record SubjectRef(String subjectType, UUID subjectId) {}

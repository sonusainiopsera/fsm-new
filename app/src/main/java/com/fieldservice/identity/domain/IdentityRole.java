package com.fieldservice.identity.domain;

/**
 * The five ratified platform roles.
 *
 * <p>This enum mirrors the {@code CHECK} constraint in the {@code role_assignment} table.
 * Any value not in this enum cannot be persisted even via a migration script.
 *
 * <p>The vocabulary is fixed pending Q11 ratification. Adding a sixth role requires a
 * coordinated migration, enum update, and security-policy review.
 */
public enum IdentityRole {
    ADMIN,
    DISPATCHER,
    TECHNICIAN,
    MANAGER,
    CUSTOMER,
    /** Restricted to the Data Protection Officer; grants access to the classification admin API. */
    PRIVACY_ADMIN
}

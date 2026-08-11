package com.fieldservice.identity.domain;

/**
 * The five ratified application roles.
 *
 * <p>Vocabulary is enforced twice: here in Java and by the CHECK constraint on the
 * {@code role_assignment.role_name} column, so an out-of-vocabulary role cannot be
 * persisted even by a direct SQL statement or migration script.
 */
public enum AppRole {
    ADMIN,
    DISPATCHER,
    TECHNICIAN,
    MANAGER,
    CUSTOMER,
    PRIVACY_ADMIN
}

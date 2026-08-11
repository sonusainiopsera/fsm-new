package com.fieldservice.identity.domain;

/**
 * User's preferred colour-scheme for the web application.
 *
 * <p>Stored as a VARCHAR(10) column on {@code app_user}; a corresponding CHECK constraint
 * in the database enforces the same vocabulary so out-of-vocabulary values cannot be
 * persisted by any code path.
 *
 * <p>Data classification: Internal (BR-23). The value is never a PII field.
 *
 * <p>{@code null} stored value resolves to {@code LIGHT} at read time so light remains
 * the default for all new accounts (BR-32).
 */
public enum AppearancePreference {
    LIGHT,
    DARK,
    SYSTEM
}

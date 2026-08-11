package com.fieldservice.identity.domain;

/**
 * Per-account appearance preference.
 *
 * <p>Data classification: <strong>Internal</strong> (BR-23). Not PII; safe to log and audit.
 *
 * <p>Resolution order (client):
 * <ol>
 *   <li>Server value (authoritative once session is established).</li>
 *   <li>Local mirror in {@code localStorage} key {@code fs-appearance}.</li>
 *   <li>OS/browser {@code prefers-color-scheme} media query.</li>
 *   <li>Fallback: {@link #LIGHT}.</li>
 * </ol>
 *
 * <p>NULL database value resolves to {@link #LIGHT} — light is the default for new accounts.
 */
public enum AppearancePreference {

    /** Light appearance — default for new accounts and the null-resolution fallback. */
    LIGHT,

    /** Dark appearance. */
    DARK,

    /**
     * Follow the OS/browser {@code prefers-color-scheme} media query.
     * The concrete appearance flips without a server write when the OS preference changes.
     */
    SYSTEM
}

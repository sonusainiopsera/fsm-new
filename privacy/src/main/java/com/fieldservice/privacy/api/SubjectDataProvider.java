package com.fieldservice.privacy.api;

import java.util.Set;

/**
 * Contract for per-module personal-data contribution to a DSAR export.
 *
 * <p>Implementations live in each owning module's narrow public-api package.
 * The privacy module discovers all registered beans and aggregates them at
 * export time — no change to the privacy module is needed when a new module
 * is added.
 *
 * <p>Contract guarantees:
 * <ul>
 *   <li>Never returns {@code null} — use {@link SubjectSection#empty} for absent data.</li>
 *   <li>Never throws for a subject that is unknown to the module — return an empty section.</li>
 *   <li>Any envelope-encrypted field is decrypted inside this implementation
 *       using the subject's data key; the privacy module never handles raw key material.</li>
 *   <li>Restricted fields (passwords, tokens) must be excluded from the section rows.</li>
 * </ul>
 */
public interface SubjectDataProvider {

    /** Stable, globally unique section name, e.g. {@code "identity.app_user"}. */
    String sectionName();

    /**
     * Subject types this provider handles.
     *
     * <p>The export aggregator will call {@link #collect} only when the request's
     * {@code subjectType} is in this set (or when the set contains {@code "*"} for
     * providers that collect data for all subject types).
     */
    Set<String> supportedSubjectTypes();

    /**
     * Collects the personal data for the given subject reference.
     *
     * @param ref the subject to collect data for
     * @return a section with all personal data rows, or an empty section if none
     */
    SubjectSection collect(SubjectRef ref);
}

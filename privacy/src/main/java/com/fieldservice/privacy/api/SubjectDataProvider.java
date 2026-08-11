package com.fieldservice.privacy.api;

import java.util.List;

/**
 * Service Provider Interface for per-module personal-data contributions to a DSAR export.
 *
 * <p>Each domain module that holds personal data about subjects implements this interface
 * as a Spring {@code @Component}. The privacy module discovers all registered beans via
 * constructor injection and delegates to them during export assembly. No change to the
 * privacy module is required when a new module is added.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Return a section with {@code rowCount=0} and an empty rows list when the subject
 *       has no data in this module — never return {@code null} or throw.</li>
 *   <li>Perform any envelope decryption of Confidential/Restricted fields internally,
 *       so the privacy module never handles raw key material.</li>
 *   <li>Be read-only and side-effect-free.</li>
 * </ul>
 *
 * <p>The privacy module never queries another module's tables directly; it only calls
 * this interface, satisfying the WO-010 ArchUnit boundary rule.
 */
public interface SubjectDataProvider {

    /**
     * Returns the stable name of the export section produced by this provider.
     * Must be unique across all registered providers.
     */
    String sectionName();

    /**
     * Returns the stable module identifier used in the export manifest
     * (e.g. {@code "identity"}, {@code "workorder"}, {@code "inventory"}).
     */
    String sourceModule();

    /**
     * Returns the subject types this provider can service.
     * The provider's {@link #collect} method is only called for subjects whose
     * type appears in this list.
     */
    List<String> supportedSubjectTypes();

    /**
     * Collects all personal data held by this module for the given subject.
     *
     * @param ref the subject reference (type + ID)
     * @return a section with row data; never null; row count may be zero
     */
    SubjectDataSection collect(SubjectRef ref);
}

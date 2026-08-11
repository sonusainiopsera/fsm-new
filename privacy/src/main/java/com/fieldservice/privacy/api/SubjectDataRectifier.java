package com.fieldservice.privacy.api;

import java.util.UUID;

/**
 * SPI for per-module field-level corrections to a data subject's personal data.
 *
 * <p>Each module that holds rectifiable personal data implements this interface
 * as a Spring {@code @Component}. The privacy module discovers all registered beans
 * and delegates corrections to the owning module; it never updates another module's
 * tables directly.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Apply all module-level validation before persisting any change.</li>
 *   <li>Trigger Hibernate Envers auditing in the same transaction so a before/after
 *       revision is written.</li>
 *   <li>Return {@link RectifyFieldResult#skipped} when the field is not owned by this
 *       module, so the orchestrator can route to the correct module.</li>
 *   <li>Return {@link RectifyFieldResult#refused} when the field is owned but the
 *       correction is invalid (e.g. format violation).</li>
 *   <li>Never write a correction when the subject's key is destroyed
 *       (check before persisting).</li>
 * </ul>
 */
public interface SubjectDataRectifier {

    /**
     * Returns the bounded context module name (e.g. {@code "identity"}, {@code "workorder"}).
     * Must match the {@code module} column value in the classification registry.
     */
    String module();

    /**
     * Returns the subject types this rectifier handles
     * (e.g. {@code "CUSTOMER"}, {@code "TECHNICIAN"}, {@code "APP_USER"}).
     */
    java.util.List<String> supportedSubjectTypes();

    /**
     * Applies a single field correction for the given subject.
     *
     * <p>Implementations run inside the caller's transaction.
     *
     * @param ref        the subject to rectify
     * @param entityName simple class name of the entity to correct
     * @param fieldName  field name on the entity
     * @param newValue   the replacement value (already validated against the allow-list tier)
     * @return the result — applied, skipped (not owned), or refused (owned but invalid)
     */
    RectifyFieldResult rectify(SubjectRef ref, String entityName, String fieldName, String newValue);

    /** Result of a single field correction attempt. */
    sealed interface RectifyFieldResult {

        /** The correction was applied. {@code revisionId} is the Envers revision produced. */
        record Applied(String entityName, String fieldName, long revisionId) implements RectifyFieldResult {}

        /** This module does not own the entity/field — route to the next rectifier. */
        record Skipped(String entityName, String fieldName, String reason) implements RectifyFieldResult {}

        /** This module owns the entity/field but the correction is invalid. */
        record Refused(String entityName, String fieldName, String reason) implements RectifyFieldResult {}

        static RectifyFieldResult applied(String entity, String field, long revisionId) {
            return new Applied(entity, field, revisionId);
        }

        static RectifyFieldResult skipped(String entity, String field, String reason) {
            return new Skipped(entity, field, reason);
        }

        static RectifyFieldResult refused(String entity, String field, String reason) {
            return new Refused(entity, field, reason);
        }
    }
}

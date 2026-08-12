package com.fieldservice.privacy.api;

import java.util.List;
import java.util.Set;

/**
 * Per-module contract for applying allow-listed field-level corrections to a subject's
 * personal data.
 *
 * <p>Implementations live in each owning module (identity, portal, workforce, workorder)
 * so module validation and Envers auditing happen inside the owning module.  The privacy
 * module orchestrates but never updates another module's tables directly.
 *
 * <p>Contract guarantees:
 * <ul>
 *   <li>Never throws for a subject unknown to this module — return a skipped result.</li>
 *   <li>Each correction is applied in the same transaction as its Envers revision so no
 *       correction can exist without an audit record.</li>
 *   <li>The rectifier must not persist corrections when the subject's envelope data key
 *       is destroyed — it must return a skipped result instead.</li>
 * </ul>
 */
public interface SubjectDataRectifier {

    /** Subject types this rectifier handles (e.g. "APP_USER", "TECHNICIAN"). */
    Set<String> supportedSubjectTypes();

    /** Entity names (simple class names) this rectifier can correct. */
    Set<String> supportedEntityNames();

    /** Stable section name matching the owning module (e.g. "identity.app_user"). */
    String sectionName();

    /**
     * Applies the given corrections to the subject's data.
     *
     * @param ref         the subject to correct
     * @param corrections the list of corrections to attempt; only those whose
     *                    {@code entityName} is in {@link #supportedEntityNames()} are attempted
     * @return one result per correction in the same order as the input list
     */
    List<FieldRectificationResult> rectify(SubjectRef ref, List<FieldCorrection> corrections);
}

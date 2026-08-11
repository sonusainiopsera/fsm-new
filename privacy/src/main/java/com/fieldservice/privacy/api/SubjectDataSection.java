package com.fieldservice.privacy.api;

import java.util.List;
import java.util.Map;

/**
 * Personal-data contribution from a single module for a DSAR export.
 *
 * <p>Each registered {@link SubjectDataProvider} returns exactly one section per
 * {@link SubjectRef}. Sections with zero rows MUST still be included in the export
 * manifest so completeness is provable.
 *
 * @param sectionName  stable name for this section in the export (e.g. {@code "work_orders"})
 * @param sourceModule module identifier (e.g. {@code "workorder"}) for the manifest
 * @param rowCount     total number of rows found; 0 means the subject has no data in this module
 * @param rows         individual data rows as key-value pairs; may be empty but never null
 */
public record SubjectDataSection(
        String sectionName,
        String sourceModule,
        int rowCount,
        List<Map<String, Object>> rows
) {

    /** Convenience factory for a section with no data for this subject. */
    public static SubjectDataSection empty(String sectionName, String sourceModule) {
        return new SubjectDataSection(sectionName, sourceModule, 0, List.of());
    }
}

package com.fieldservice.privacy.api;

import java.util.List;
import java.util.Map;

/**
 * One module's personal-data contribution for a given subject.
 *
 * <p>Every registered {@link SubjectDataProvider} must return a section even
 * when there is no data for the subject — use an empty {@code rows} list and
 * {@code rowCount = 0} so the manifest proves completeness by enumeration.
 *
 * @param sectionName  stable section identifier (e.g. "identity.app_user")
 * @param sourceModule owning module name (e.g. "identity")
 * @param schemaVersion provider's schema version for forward compatibility
 * @param rowCount     number of personal-data rows returned
 * @param rows         each row is a field-name → masked value map;
 *                     Restricted fields must be omitted or replaced with null
 */
public record SubjectSection(
        String              sectionName,
        String              sourceModule,
        int                 schemaVersion,
        long                rowCount,
        List<Map<String, Object>> rows
) {
    public static SubjectSection empty(String sectionName, String sourceModule) {
        return new SubjectSection(sectionName, sourceModule, 1, 0, List.of());
    }
}

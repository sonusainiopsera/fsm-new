package com.fieldservice.analytics.internal.quality;

import org.springframework.lang.Nullable;

/**
 * Derives the structured {@code fault_key} from work order fault identity fields.
 *
 * <p>Fault-key derivation rule (normative — mirrors V32 migration comment):
 * <ol>
 *   <li>If {@code faultCode} is non-blank: {@code fault_key = upper(trim(faultCode))}</li>
 *   <li>Else if {@code faultCategory} is non-blank: {@code fault_key = "CAT:" + upper(trim(faultCategory))}</li>
 *   <li>Otherwise: work order is UNCLASSIFIABLE — method returns {@code null}.</li>
 * </ol>
 *
 * <p>Free-text fault descriptions are never used for matching (as required by the WO constraint).
 *
 * <p>A non-null {@code fault_key} combined with a non-null {@code asset_id} is the
 * minimum identity needed to link repeat visits.
 */
final class FaultKeyDeriver {

    private FaultKeyDeriver() {}

    /**
     * Returns the derived fault key or {@code null} if the work order is UNCLASSIFIABLE.
     *
     * @param faultCode     structured fault code from work_order.fault_code (may be null)
     * @param faultCategory fault category code from work_order.fault_category (may be null)
     * @return the fault key string, or {@code null} for UNCLASSIFIABLE
     */
    @Nullable
    static String derive(@Nullable String faultCode, @Nullable String faultCategory) {
        if (faultCode != null && !faultCode.isBlank()) {
            return faultCode.trim().toUpperCase();
        }
        if (faultCategory != null && !faultCategory.isBlank()) {
            return "CAT:" + faultCategory.trim().toUpperCase();
        }
        return null;
    }

    /**
     * Returns true when the work order is classifiable (has at least one fault identity field).
     */
    static boolean isClassifiable(@Nullable String faultCode, @Nullable String faultCategory) {
        return derive(faultCode, faultCategory) != null;
    }
}

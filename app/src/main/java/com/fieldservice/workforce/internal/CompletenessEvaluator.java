package com.fieldservice.workforce.internal;

import com.fieldservice.workforce.api.CertificationSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure stateless evaluation of technician completeness against a set of
 * {@link ReadinessRequirementEntity} rules.
 *
 * <p>No Spring dependencies — all methods are static so this class can be
 * tested without application context.
 *
 * <h3>Completeness rules</h3>
 * <ul>
 *   <li>PROFILE_FIELD — the named field on the technician must be non-null and non-blank.
 *       Unknown field names are treated as missing (fail-closed).</li>
 *   <li>CERTIFICATION_TYPE — the technician must hold at least one <em>current</em>
 *       certification of the required type code.
 *       If a cert exists but is not current → EXPIRED.
 *       If no cert of that type exists at all → MISSING.</li>
 *   <li>A cert expiring within {@code warningWindowDays} is still <em>current</em>:
 *       the technician is complete but the type code appears in
 *       {@code expiringSoonCertificationTypes}.</li>
 * </ul>
 */
final class CompletenessEvaluator {

    private CompletenessEvaluator() {}

    static TechnicianCompletenessResult evaluate(
            TechnicianReadModel tech,
            List<CertificationSummary> allCerts,
            List<ReadinessRequirementEntity> requirements,
            int warningWindowDays) {

        List<String> missingFields                = new ArrayList<>();
        List<String> missingCertificationTypes    = new ArrayList<>();
        List<String> expiredCertificationTypes    = new ArrayList<>();
        List<String> expiringSoonCertificationTypes = new ArrayList<>();

        for (ReadinessRequirementEntity req : requirements) {
            if (req.getRequirementKind() == ReadinessRequirementKind.PROFILE_FIELD) {
                String fieldName = req.getFieldName();
                if (!isFieldPopulated(tech, fieldName)) {
                    missingFields.add(fieldName);
                }
            } else if (req.getRequirementKind() == ReadinessRequirementKind.CERTIFICATION_TYPE) {
                String typeCode = req.getCertificationTypeCode();
                classifyCertGap(typeCode, allCerts, warningWindowDays,
                        missingCertificationTypes, expiredCertificationTypes,
                        expiringSoonCertificationTypes);
            }
        }

        boolean complete = missingFields.isEmpty()
                && missingCertificationTypes.isEmpty()
                && expiredCertificationTypes.isEmpty();

        return new TechnicianCompletenessResult(
                tech.id(),
                tech.employeeCode(),
                tech.displayName(),
                complete,
                List.copyOf(missingFields),
                List.copyOf(missingCertificationTypes),
                List.copyOf(expiredCertificationTypes),
                List.copyOf(expiringSoonCertificationTypes));
    }

    /**
     * Returns {@code true} iff the named field on {@code tech} is non-null and non-blank.
     * Unknown field names return {@code false} (fail-closed).
     */
    static boolean isFieldPopulated(TechnicianReadModel tech, String fieldName) {
        if (fieldName == null) return false;
        return switch (fieldName) {
            case "employeeCode"   -> notBlank(tech.employeeCode());
            case "displayName"    -> notBlank(tech.displayName());
            case "mobilePhone"    -> notBlank(tech.mobilePhone());
            case "timezone"       -> notBlank(tech.timezone());
            case "homeBaseSiteId" -> tech.homeBaseSiteId() != null;
            default               -> false; // unknown field → fail-closed
        };
    }

    // ---- private helpers ----------------------------------------------------

    private static void classifyCertGap(
            String typeCode,
            List<CertificationSummary> allCerts,
            int warningWindowDays,
            List<String> missingOut,
            List<String> expiredOut,
            List<String> expiringSoonOut) {

        List<CertificationSummary> matching = allCerts.stream()
                .filter(c -> typeCode.equals(c.typeCode()))
                .collect(Collectors.toList());

        if (matching.isEmpty()) {
            missingOut.add(typeCode);
            return;
        }

        // Check if ANY matching cert is current
        boolean anyCurrent = matching.stream().anyMatch(CertificationSummary::current);
        if (!anyCurrent) {
            expiredOut.add(typeCode);
            return;
        }

        // Current — check whether expiring soon (daysUntilExpiry non-null and within window)
        boolean expiringSoon = matching.stream()
                .filter(CertificationSummary::current)
                .anyMatch(c -> c.daysUntilExpiry() != null
                        && c.daysUntilExpiry() <= warningWindowDays);
        if (expiringSoon) {
            expiringSoonOut.add(typeCode);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

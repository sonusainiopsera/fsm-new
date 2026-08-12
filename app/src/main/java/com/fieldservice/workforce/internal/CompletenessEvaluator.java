package com.fieldservice.workforce.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Framework-free completeness evaluator for a single technician (AC-10).
 *
 * <p>Accepts pre-loaded profile and certification data so no Spring context is needed.
 * Currency is derived using the same rule as {@link com.fieldservice.workforce.application.CertificationEvaluator}:
 * {@code expires_on >= atDate} (inclusive). A null expires_on means perpetual.
 *
 * <p>The caller is responsible for loading the requirement set and technician data.
 * This class contains only pure evaluation logic.
 */
public class CompletenessEvaluator {

    /** Profile fields that can be checked by the PROFILE_FIELD requirement kind. */
    private static final Map<String, java.util.function.Function<TechnicianProfile, String>> FIELD_EXTRACTORS =
            Map.of(
                    "employee_no",   TechnicianProfile::employeeNo,
                    "display_name",  TechnicianProfile::displayName,
                    "email",         TechnicianProfile::email,
                    "phone",         TechnicianProfile::phone
            );

    // ── Input value objects ──────────────────────────────────────────────────

    /**
     * Flat profile snapshot for a single technician (no JPA entities).
     *
     * @param technicianId UUID of the technician
     * @param employeeNo   employee number (nullable)
     * @param displayName  display name from app_user (nullable)
     * @param email        email from app_user (nullable)
     * @param phone        phone from technician (nullable)
     * @param category     technician category (nullable — unratified)
     */
    public record TechnicianProfile(
            java.util.UUID technicianId,
            String employeeNo,
            String displayName,
            String email,
            String phone,
            String category) {}

    /**
     * Minimal cert snapshot for evaluation (no JPA loading required).
     *
     * @param typeCode  certification type code
     * @param expiresOn expiry date (null = perpetual)
     * @param active    whether the cert record is active
     */
    public record CertEntry(String typeCode, LocalDate expiresOn, boolean active) {}

    /**
     * A single configured requirement.
     *
     * @param kind                PROFILE_FIELD or CERTIFICATION_TYPE
     * @param fieldName           set when kind = PROFILE_FIELD
     * @param certificationTypeCode set when kind = CERTIFICATION_TYPE
     */
    public record RequirementSpec(RequirementKind kind,
                                  String fieldName,
                                  String certificationTypeCode) {}

    /** Per-technician completeness evaluation result. */
    public record TechnicianCompletenessResult(
            java.util.UUID technicianId,
            String employeeCode,
            String displayName,
            /** Mandatory profile fields with null or blank values. */
            List<String> missingFields,
            /** Required certification types with no active record at all. */
            List<String> missingCertificationTypes,
            /** Required certification types present but expired (expiresOn < atDate). */
            List<String> expiredCertificationTypes,
            /** Required certification types present, current, but expiring within warningWindowDays. */
            List<String> expiringSoonCertificationTypes
    ) {
        /** Returns true only when all lists are empty (fully compliant). */
        public boolean isComplete() {
            return missingFields.isEmpty()
                    && missingCertificationTypes.isEmpty()
                    && expiredCertificationTypes.isEmpty();
        }
    }

    // ── Evaluation ───────────────────────────────────────────────────────────

    /**
     * Evaluates completeness for a single technician.
     *
     * @param profile            technician profile snapshot
     * @param certs              all active certification records for the technician
     * @param requirements       active requirements to check against
     * @param atDate             business date for currency evaluation
     * @param warningWindowDays  certs expiring within this many days appear in expiringSoon list
     * @return completeness result (never null)
     */
    public TechnicianCompletenessResult evaluate(
            TechnicianProfile profile,
            List<CertEntry> certs,
            List<RequirementSpec> requirements,
            LocalDate atDate,
            int warningWindowDays) {

        List<String> missingFields            = new ArrayList<>();
        List<String> missingCertTypes         = new ArrayList<>();
        List<String> expiredCertTypes         = new ArrayList<>();
        List<String> expiringSoonCertTypes    = new ArrayList<>();

        for (RequirementSpec req : requirements) {
            if (req.kind() == RequirementKind.PROFILE_FIELD) {
                evaluateProfileField(profile, req.fieldName(), missingFields);
            } else {
                evaluateCertificationType(certs, req.certificationTypeCode(),
                        atDate, warningWindowDays,
                        missingCertTypes, expiredCertTypes, expiringSoonCertTypes);
            }
        }

        return new TechnicianCompletenessResult(
                profile.technicianId(),
                profile.employeeNo(),
                profile.displayName(),
                List.copyOf(missingFields),
                List.copyOf(missingCertTypes),
                List.copyOf(expiredCertTypes),
                List.copyOf(expiringSoonCertTypes));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void evaluateProfileField(TechnicianProfile profile, String fieldName,
                                       List<String> missingFields) {
        var extractor = FIELD_EXTRACTORS.get(fieldName);
        if (extractor == null) {
            // Unknown field name in requirement — treat as missing (fail-closed, AC-2)
            missingFields.add(fieldName);
            return;
        }
        String value = extractor.apply(profile);
        if (value == null || value.isBlank()) {
            missingFields.add(fieldName);
        }
    }

    private void evaluateCertificationType(
            List<CertEntry> certs,
            String typeCode,
            LocalDate atDate,
            int warningWindowDays,
            List<String> missingCertTypes,
            List<String> expiredCertTypes,
            List<String> expiringSoonCertTypes) {

        // Filter to active records for this type
        List<CertEntry> matching = certs.stream()
                .filter(c -> c.active() && typeCode.equals(c.typeCode()))
                .toList();

        if (matching.isEmpty()) {
            missingCertTypes.add(typeCode);
            return;
        }

        // Find best (most-favourable) entry: prefer current over expired
        boolean hasCurrent = false;
        boolean hasExpired  = false;
        LocalDate bestExpiresOn = null;

        for (CertEntry entry : matching) {
            if (isCurrent(entry, atDate)) {
                hasCurrent = true;
                // Track the furthest expiry for expiring-soon check
                if (entry.expiresOn() == null) {
                    bestExpiresOn = null; // perpetual — never expiring soon
                    break;
                }
                if (bestExpiresOn == null || entry.expiresOn().isAfter(bestExpiresOn)) {
                    bestExpiresOn = entry.expiresOn();
                }
            } else {
                hasExpired = true;
            }
        }

        if (hasCurrent) {
            // The technician holds a current cert — check if expiring soon
            if (bestExpiresOn != null) {
                long daysUntilExpiry = ChronoUnit.DAYS.between(atDate, bestExpiresOn);
                if (daysUntilExpiry <= warningWindowDays) {
                    expiringSoonCertTypes.add(typeCode);
                }
            }
            // NOTE: expiringSoon does NOT count as incomplete — gate checks missingFields + missingCertTypes + expiredCertTypes
        } else if (hasExpired) {
            // All matching certs are expired
            expiredCertTypes.add(typeCode);
        } else {
            missingCertTypes.add(typeCode);
        }
    }

    private boolean isCurrent(CertEntry entry, LocalDate atDate) {
        if (entry.expiresOn() == null) return true;
        // Inclusive: certified until the 30th → eligible on the 30th
        return !entry.expiresOn().isBefore(atDate);
    }
}

package com.fieldservice.workforce.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-technician gap detail in the readiness report (AC-5, API contract).
 *
 * <p>Contains only employee code and display name — no phone, position, or PII beyond
 * what MANAGER/ADMIN roles are authorised to see for remediation purposes (AC-8).
 */
public record TechnicianGapRecord(
        UUID technicianId,
        String employeeCode,
        String displayName,
        List<String> missingFields,
        List<String> missingCertificationTypes,
        List<String> expiredCertificationTypes,
        List<String> expiringSoonCertificationTypes
) {}

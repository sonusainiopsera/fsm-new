package com.fieldservice.workforce.web;

import java.util.List;
import java.util.UUID;

public record TechnicianGapResponse(
        UUID         technicianId,
        String       employeeCode,
        String       displayName,
        List<String> missingFields,
        List<String> missingCertificationTypes,
        List<String> expiredCertificationTypes,
        List<String> expiringSoonCertificationTypes
) {}

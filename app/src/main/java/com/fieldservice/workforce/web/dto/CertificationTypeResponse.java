package com.fieldservice.workforce.web.dto;

import java.util.UUID;

public record CertificationTypeResponse(
        UUID id,
        String code,
        String displayName,
        boolean regulated,
        Integer defaultValidityMonths,
        boolean active,
        Integer version
) {}

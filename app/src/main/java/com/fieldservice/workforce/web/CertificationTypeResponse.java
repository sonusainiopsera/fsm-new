package com.fieldservice.workforce.web;

import java.util.UUID;

/** Read projection for a certification type. */
public record CertificationTypeResponse(
        UUID    id,
        String  code,
        String  displayName,
        boolean regulated,
        Integer defaultValidityMonths,
        boolean active
) {}

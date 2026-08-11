package com.fieldservice.catalog.application;

import java.math.BigDecimal;

/** Immutable command object for creating a site under a customer. */
public record CreateSiteCommand(
        String siteCode,
        String displayName,
        String address,
        String postcode,
        BigDecimal latitude,
        BigDecimal longitude,
        String accessNotes
) {
}

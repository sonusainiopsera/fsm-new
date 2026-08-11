package com.fieldservice.catalog.web;

import com.fieldservice.site.domain.Site;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SiteResponse(
        UUID       id,
        UUID       customerId,
        String     siteCode,
        String     displayName,
        String     addressLine1,
        String     city,
        String     postcode,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean    active,
        Instant    createdAt
) {
    public static SiteResponse from(Site s) {
        return new SiteResponse(
                s.getId(),
                s.getCustomerId(),
                s.getSiteCode(),
                s.getDisplayName() != null ? s.getDisplayName() : s.getName(),
                s.getAddressLine1(),
                s.getCity(),
                s.getPostcode(),
                s.getLatitude(),
                s.getLongitude(),
                s.isActive(),
                s.getCreatedAt()
        );
    }
}

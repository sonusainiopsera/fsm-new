package com.fieldservice.catalog.event;

import java.util.UUID;

public record SiteChanged(
        UUID   siteId,
        UUID   customerId,
        String siteCode,
        boolean active,
        String changeType
) {}

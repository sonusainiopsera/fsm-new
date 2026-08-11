package com.fieldservice.catalog.event;

import java.util.UUID;

public record AssetChanged(
        UUID   assetId,
        UUID   siteId,
        String assetTag,
        boolean active,
        String changeType
) {}

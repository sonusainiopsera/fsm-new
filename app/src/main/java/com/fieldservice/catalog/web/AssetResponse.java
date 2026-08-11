package com.fieldservice.catalog.web;

import com.fieldservice.asset.domain.Asset;

import java.time.Instant;
import java.util.UUID;

public record AssetResponse(
        UUID    id,
        UUID    siteId,
        String  assetTag,
        String  manufacturer,
        String  model,
        String  serialNumber,
        String  category,
        boolean active,
        Instant createdAt
) {
    public static AssetResponse from(Asset a) {
        return new AssetResponse(
                a.getId(),
                a.getSiteId(),
                a.getAssetTag(),
                a.getManufacturer(),
                a.getModel(),
                a.getSerialNumber(),
                a.getCategory(),
                a.isActive(),
                a.getCreatedAt()
        );
    }
}

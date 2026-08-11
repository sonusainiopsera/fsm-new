package com.fieldservice.workorder.application;

import java.util.UUID;

/**
 * Thrown when the supplied asset is not located at the supplied site.
 * Maps to HTTP 422 via the global exception handler with code ASSET_SITE_MISMATCH.
 */
public class AssetSiteMismatchException extends RuntimeException {

    private final UUID assetId;
    private final UUID siteId;

    public AssetSiteMismatchException(UUID assetId, UUID siteId) {
        super("Asset " + assetId + " is not at site " + siteId);
        this.assetId = assetId;
        this.siteId  = siteId;
    }

    public UUID getAssetId() { return assetId; }
    public UUID getSiteId()  { return siteId; }
}

package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * View of a DSAR export artifact for the download endpoint response.
 *
 * @param artifactId       artifact row primary key
 * @param dsarRequestId    parent request id
 * @param manifest         per-section summary from the manifest JSONB
 * @param downloadToken    short-lived token for the authenticated download
 * @param expiresAt        instant when the download token expires
 * @param expiresInSeconds seconds remaining until the download token expires
 */
public record ExportArtifactView(
        UUID                  artifactId,
        UUID                  dsarRequestId,
        List<ManifestEntry>   manifest,
        String                downloadToken,
        Instant               expiresAt,
        long                  expiresInSeconds
) {
    /** One line in the export manifest — one entry per registered provider. */
    public record ManifestEntry(
            String sectionName,
            String sourceModule,
            long   rowCount
    ) {}
}

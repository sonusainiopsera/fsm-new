package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.ExportArtifactView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON response body for GET /api/v1/privacy/dsar-requests/{id}/export.
 */
public record ExportArtifactResponse(
        UUID                  artifactId,
        UUID                  dsarRequestId,
        List<ManifestEntry>   manifest,
        String                downloadToken,
        Instant               expiresAt,
        long                  expiresInSeconds
) {
    record ManifestEntry(String sectionName, String sourceModule, long rowCount) {}

    static ExportArtifactResponse from(ExportArtifactView view) {
        List<ManifestEntry> entries = view.manifest().stream()
                .map(m -> new ManifestEntry(m.sectionName(), m.sourceModule(), m.rowCount()))
                .toList();
        return new ExportArtifactResponse(
                view.artifactId(), view.dsarRequestId(), entries,
                view.downloadToken(), view.expiresAt(), view.expiresInSeconds());
    }
}

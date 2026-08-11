package com.fieldservice.privacy.api;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Response from {@code GET /api/v1/privacy/dsar-requests/{id}/export}.
 *
 * <p>The {@code downloadUrl} is a short-lived authenticated URL valid for at most
 * 300 seconds. Callers must not cache or persist this URL.
 *
 * @param artifactId     UUID of the export artifact
 * @param manifest       per-section metadata showing completeness
 * @param downloadUrl    short-lived URL for the export bundle (max 300s validity)
 * @param expiresInSeconds remaining validity of the download URL in seconds
 */
public record ExportResponse(
        UUID artifactId,
        List<ManifestEntry> manifest,
        String downloadUrl,
        int expiresInSeconds
) {

    /**
     * One entry per registered {@link SubjectDataProvider} in the export manifest.
     *
     * @param sectionName  stable section name
     * @param sourceModule contributing module identifier
     * @param rowCount     number of personal-data rows in this section (0 = no data found)
     */
    public record ManifestEntry(String sectionName, String sourceModule, int rowCount) {}
}

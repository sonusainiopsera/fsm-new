package com.fieldservice.privacy.internal;

/**
 * Port for persisting and retrieving DSAR export bundles.
 *
 * <p>The production implementation writes to S3-compatible object storage.
 * Tests and development use the {@link InMemoryExportStorage} implementation.
 *
 * <p>All keys are namespaced by the export job to avoid collisions.
 * Storage backends must not expose unencrypted content to unauthenticated callers;
 * the platform enforces this via the authenticated download endpoint.
 */
interface ExportStoragePort {

    /**
     * Persists {@code content} under the given key and returns the canonical storage key.
     *
     * @param key     proposed key (may be adjusted by the backend)
     * @param content the export JSON bytes
     * @return the canonical key under which the content was stored
     */
    String store(String key, byte[] content);

    /**
     * Retrieves the content stored under {@code key}.
     *
     * @return the stored bytes, or {@code null} if not found or already disposed
     */
    byte[] retrieve(String key);

    /**
     * Deletes the content stored under {@code key}.
     * A no-op if the key does not exist.
     */
    void delete(String key);

    /**
     * Returns a URL that grants unauthenticated read access to the content for at most
     * {@code expirySeconds} seconds.
     *
     * <p>For the in-memory implementation this returns a local API endpoint URL;
     * for S3 backends this would be a presigned GET URL.
     */
    String generateDownloadUrl(String key, int expirySeconds);
}

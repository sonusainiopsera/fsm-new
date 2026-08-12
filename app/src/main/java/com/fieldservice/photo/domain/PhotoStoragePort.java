package com.fieldservice.photo.domain;

import java.time.Duration;

/**
 * Storage port for presigned object storage operations.
 *
 * <p>The production implementation delegates to S3-compatible storage via
 * {@link com.fieldservice.photo.infrastructure.S3PhotoStorageAdapter}.
 * A no-network stub is active in development and tests.
 *
 * <p>Security invariants enforced by all implementations:
 * <ul>
 *   <li>Presigned PUT and GET URLs must never appear in any log line.</li>
 *   <li>No operation may accept or return image binary content.</li>
 *   <li>Objects written through this port are encrypted at rest.</li>
 * </ul>
 */
public interface PhotoStoragePort {

    /**
     * Issues a presigned PUT for {@code key} permitting a single upload of the declared
     * content type within the given validity window.
     *
     * @throws PhotoStorageException if the storage provider is unavailable
     */
    PresignedPut presignPut(String key, String contentType, long maxBytes, Duration validity);

    /**
     * Issues a short-lived presigned GET for {@code key}.
     *
     * @throws PhotoStorageException if the storage provider is unavailable
     */
    String presignGet(String key, Duration validity);

    /**
     * Returns metadata for the object at {@code key} without fetching its content.
     *
     * @return metadata, or {@code null} if the object does not exist
     * @throws PhotoStorageException if the storage provider is unavailable
     */
    ObjectMetadata headObject(String key);

    /**
     * Deletes the object at {@code key}.
     * A no-op if the key does not exist.
     *
     * @throws PhotoStorageException if the storage provider is unavailable
     */
    void deleteObject(String key);

    record PresignedPut(String uploadUrl, String storageKey) {}

    record ObjectMetadata(long contentLength, String contentType) {}
}

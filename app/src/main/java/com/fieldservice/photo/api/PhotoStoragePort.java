package com.fieldservice.photo.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Port interface for object storage operations on work order photos.
 *
 * <p>Isolates cloud-provider specifics to a single adapter class so the application tier
 * has no SDK import for AWS/Azure. Implementations must not log presigned URLs.
 *
 * <p>Availability contract: implementations throw {@link PhotoStorageUnavailableException}
 * on provider errors so callers can render a 503 with a retry affordance without leaking
 * provider-specific exception detail.
 */
public interface PhotoStoragePort {

    /**
     * Issues a presigned PUT URL for the given storage key.
     *
     * @param storageKey    the object key (e.g. work-orders/{workOrderId}/{uuidv7}.jpg)
     * @param contentType   the required Content-Type the client must use when PUTting
     * @param expirySeconds how long the URL is valid for (typically 300)
     * @return result containing uploadUrl and expiry instant; never null
     * @throws PhotoStorageUnavailableException if the provider is unreachable
     */
    PresignedPutResult presignPut(String storageKey, String contentType, int expirySeconds);

    /**
     * Issues a short-lived presigned GET URL for the given storage key.
     *
     * @param storageKey    the object key
     * @param expirySeconds how long the URL is valid (typically 60)
     * @return presigned GET URL; never null
     * @throws PhotoStorageUnavailableException if the provider is unreachable
     */
    String presignGet(String storageKey, int expirySeconds);

    /**
     * Returns the metadata for the object identified by storageKey, or empty if it
     * does not exist. This is the verification call before accepting a registration.
     *
     * @param storageKey the object key
     * @return object metadata if present
     * @throws PhotoStorageUnavailableException if the provider is unreachable
     */
    Optional<ObjectMetadata> headObject(String storageKey);

    /** Result of a presign-PUT operation. */
    record PresignedPutResult(String uploadUrl, Instant expiresAt) {}

    /** HEAD-object response describing a stored object. */
    record ObjectMetadata(String contentType, long contentLength) {}
}

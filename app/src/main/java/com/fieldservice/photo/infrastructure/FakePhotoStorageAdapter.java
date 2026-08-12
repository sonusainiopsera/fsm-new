package com.fieldservice.photo.infrastructure;

import com.fieldservice.photo.api.PhotoStoragePort;
import com.fieldservice.photo.api.PhotoStorageUnavailableException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub storage adapter for development and test profiles.
 *
 * <p>Returns plausible but non-functional presigned URLs. headObject returns the metadata
 * registered via {@link #registerObject(String, String, long)} so integration tests can
 * simulate a successfully uploaded object without real object storage.
 *
 * <p>In production this bean is replaced by the S3-compatible adapter.
 */
@Component
@Profile("!prod")
public class FakePhotoStorageAdapter implements PhotoStoragePort {

    private static final String FAKE_BUCKET = "https://fake-storage.local";

    /** In-memory store of uploaded objects: key → metadata. */
    private final Map<String, ObjectMetadata> uploadedObjects = new ConcurrentHashMap<>();

    @Override
    public PresignedPutResult presignPut(String storageKey, String contentType, int expirySeconds) {
        Instant expiresAt = Instant.now().plusSeconds(expirySeconds);
        // URL does not contain presigned query params intentionally — this is a stub.
        String uploadUrl = FAKE_BUCKET + "/" + storageKey + "?X-Fake-Expires=" + expiresAt.getEpochSecond();
        return new PresignedPutResult(uploadUrl, expiresAt);
    }

    @Override
    public String presignGet(String storageKey, int expirySeconds) {
        Instant expiresAt = Instant.now().plusSeconds(expirySeconds);
        return FAKE_BUCKET + "/" + storageKey + "?X-Fake-Read-Expires=" + expiresAt.getEpochSecond();
    }

    @Override
    public Optional<ObjectMetadata> headObject(String storageKey) {
        return Optional.ofNullable(uploadedObjects.get(storageKey));
    }

    /**
     * Test helper: simulate a successful direct PUT to object storage.
     *
     * @param storageKey    the object key
     * @param contentType   content type of the uploaded object
     * @param contentLength byte size of the uploaded object
     */
    public void registerObject(String storageKey, String contentType, long contentLength) {
        uploadedObjects.put(storageKey, new ObjectMetadata(contentType, contentLength));
    }

    /**
     * Test helper: remove a simulated object (e.g. to test missing-object scenarios).
     */
    public void removeObject(String storageKey) {
        uploadedObjects.remove(storageKey);
    }
}

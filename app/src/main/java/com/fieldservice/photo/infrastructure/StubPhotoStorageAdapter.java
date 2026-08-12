package com.fieldservice.photo.infrastructure;

import com.fieldservice.photo.domain.PhotoStorageException;
import com.fieldservice.photo.domain.PhotoStoragePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub implementation of {@link PhotoStoragePort} for development and tests.
 *
 * <p>Active when no other {@link PhotoStoragePort} bean is registered (i.e. when
 * {@code app.storage.s3.bucket-name} is not configured).
 *
 * <p>The presigned PUT URL points to a local no-op endpoint. No actual object storage
 * write is performed. {@link #headObject(String)} returns metadata for objects that
 * were explicitly registered via {@link #stubRegister(String, long, String)}.
 */
@Component
@ConditionalOnMissingBean(value = PhotoStoragePort.class, ignored = StubPhotoStorageAdapter.class)
public class StubPhotoStorageAdapter implements PhotoStoragePort {

    private final ConcurrentHashMap<String, ObjectMetadata> store = new ConcurrentHashMap<>();

    @Override
    public PresignedPut presignPut(String key, String contentType, long maxBytes, Duration validity) {
        String fakeUrl = "http://localhost:8080/api/v1/stub/photo-upload?key=" + key;
        return new PresignedPut(fakeUrl, key);
    }

    @Override
    public String presignGet(String key, Duration validity) {
        return "http://localhost:8080/api/v1/stub/photo-download?key=" + key;
    }

    @Override
    public ObjectMetadata headObject(String key) {
        return store.get(key);
    }

    @Override
    public void deleteObject(String key) {
        store.remove(key);
    }

    /**
     * Test helper: registers a stub object so that {@link #headObject} returns real metadata.
     */
    public void stubRegister(String key, long contentLength, String contentType) {
        store.put(key, new ObjectMetadata(contentLength, contentType));
    }
}

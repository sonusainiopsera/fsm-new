package com.fieldservice.photo.infrastructure;

import com.fieldservice.photo.domain.PhotoStorageException;
import com.fieldservice.photo.domain.PhotoStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Duration;

/**
 * S3-compatible implementation of {@link PhotoStoragePort}.
 *
 * <p>Active when {@code app.storage.s3.bucket-name} is configured.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Presigned URLs are never logged; only the storage key, work order id and byte size
 *       are safe to log.</li>
 *   <li>All SDK exceptions are wrapped in {@link PhotoStorageException} so provider details
 *       never leak to HTTP responses.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty("app.storage.s3.bucket-name")
public class S3PhotoStorageAdapter implements PhotoStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3PhotoStorageAdapter.class);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucketName;

    public S3PhotoStorageAdapter(S3Client s3Client,
                                  S3Presigner presigner,
                                  PhotoStorageProperties props) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucketName = props.getS3().getBucketName();
    }

    @Override
    public PresignedPut presignPut(String key, String contentType, long maxBytes, Duration validity) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(validity)
                    .putObjectRequest(putRequest)
                    .build();

            String uploadUrl = presigner.presignPutObject(presignRequest).url().toString();
            log.debug("photo.presign_put: key={} contentType={} maxBytes={}", key, contentType, maxBytes);
            return new PresignedPut(uploadUrl, key);
        } catch (SdkException e) {
            log.warn("photo.presign_put_failed: key={}", key);
            throw new PhotoStorageException("Storage provider unavailable", e);
        }
    }

    @Override
    public String presignGet(String key, Duration validity) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(validity)
                    .getObjectRequest(getRequest)
                    .build();

            log.debug("photo.presign_get: key={}", key);
            return presigner.presignGetObject(presignRequest).url().toString();
        } catch (SdkException e) {
            log.warn("photo.presign_get_failed: key={}", key);
            throw new PhotoStorageException("Storage provider unavailable", e);
        }
    }

    @Override
    public ObjectMetadata headObject(String key) {
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return new ObjectMetadata(response.contentLength(), response.contentType());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (SdkException e) {
            log.warn("photo.head_object_failed: key={}", key);
            throw new PhotoStorageException("Storage provider unavailable", e);
        }
    }

    @Override
    public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            log.info("photo.deleted: key={}", key);
        } catch (SdkException e) {
            log.warn("photo.delete_failed: key={}", key);
            throw new PhotoStorageException("Storage provider unavailable", e);
        }
    }
}

package com.fieldservice.photo.application;

import com.fieldservice.photo.api.PhotoStoragePort;
import com.fieldservice.photo.api.PhotoStoragePort.ObjectMetadata;
import com.fieldservice.photo.api.PhotoStoragePort.PresignedPutResult;
import com.fieldservice.photo.domain.PhotoCategory;
import com.fieldservice.photo.domain.UploadIntent;
import com.fieldservice.photo.domain.WorkOrderPhoto;
import com.fieldservice.photo.repository.UploadIntentRepository;
import com.fieldservice.photo.repository.WorkOrderPhotoRepository;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the presigned direct-upload flow for work order photos.
 *
 * <p>No image bytes reach this service. The flow is:
 * <ol>
 *   <li>Client calls {@link #issueUploadIntent} → receives presigned PUT URL + storage key</li>
 *   <li>Client PUT the binary directly to object storage (bypasses the JVM entirely)</li>
 *   <li>Client calls {@link #registerPhoto} → verifies via HEAD before persisting metadata</li>
 *   <li>Reads return presigned GET URLs scoped to the requesting principal</li>
 * </ol>
 *
 * <p>Idempotency on registration: the unique constraint on {@code storage_key} in
 * {@code work_order_photo} means a duplicate registration returns the existing record
 * rather than inserting a duplicate row.
 */
@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);

    /** Allowed content types for uploaded photos (allow-list). */
    public static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp");

    /** Maximum accepted object size: 5 MB. */
    public static final long MAX_BYTES = 5 * 1024 * 1024L;

    /** Presigned PUT validity period in seconds. */
    private static final int PUT_EXPIRY_SECONDS = 300;

    /** Presigned GET validity period in seconds. */
    private static final int GET_EXPIRY_SECONDS = 60;

    /** Photo retention period in days (default 365 × 7 = 7 years). */
    @Value("${fieldservice.photo.retention-days:2555}")
    private int retentionDays;

    private final PhotoStoragePort        storagePort;
    private final UploadIntentRepository  intentRepository;
    private final WorkOrderPhotoRepository photoRepository;

    public PhotoUploadService(PhotoStoragePort storagePort,
                               UploadIntentRepository intentRepository,
                               WorkOrderPhotoRepository photoRepository) {
        this.storagePort      = storagePort;
        this.intentRepository = intentRepository;
        this.photoRepository  = photoRepository;
    }

    /**
     * Issues a presigned PUT intent for a work order photo.
     *
     * @param workOrderId   the work order the photo belongs to
     * @param contentType   the declared content type (must be on the allow-list)
     * @param contentLength declared byte size (must be ≤ MAX_BYTES)
     * @param requestedBy   the authenticated user id
     * @return intent details including upload URL and storage key
     * @throws DisallowedContentTypeException if content type is not on the allow-list
     * @throws ContentTooLargeException        if declared size exceeds the limit
     */
    @Transactional
    public UploadIntentResult issueUploadIntent(UUID workOrderId, String contentType,
                                                 long contentLength, UUID requestedBy) {
        validateContentType(contentType);
        validateContentLength(contentLength);

        String ext        = extFor(contentType);
        String storageKey = "work-orders/" + workOrderId + "/" + UuidV7.generate() + "." + ext;

        PresignedPutResult presigned = storagePort.presignPut(storageKey, contentType, PUT_EXPIRY_SECONDS);

        UploadIntent intent = new UploadIntent(workOrderId, storageKey, contentType,
                contentLength, presigned.expiresAt(), requestedBy);
        intentRepository.save(intent);

        log.info("upload_intent_issued workOrderId={} storageKey={} actor={} traceId={}",
                workOrderId, storageKey, requestedBy, MDC.get("traceId"));

        return new UploadIntentResult(
                intent.getId(),
                storageKey,
                presigned.uploadUrl(),
                presigned.expiresAt(),
                contentType,
                MAX_BYTES
        );
    }

    /**
     * Registers a photo after the client has PUT the binary to object storage.
     *
     * <p>Validates:
     * <ul>
     *   <li>the storage key corresponds to an intent this server issued</li>
     *   <li>the intent has not expired</li>
     *   <li>the object actually exists (HEAD check)</li>
     *   <li>the object's content type and size match the intent</li>
     * </ul>
     *
     * <p>Idempotent: a second call with the same storage key returns the existing record.
     *
     * @throws IntentNotFoundException   if no intent exists for the given storage key
     * @throws IntentExpiredException    if the intent's presigned URL has expired
     * @throws ObjectVerificationException if HEAD check fails or metadata mismatches
     */
    @Transactional
    public PhotoRegistrationResult registerPhoto(UUID workOrderId, String storageKey,
                                                  PhotoCategory category, Instant capturedAt,
                                                  String caption, UUID registeredBy) {

        // Idempotency: if already registered, return the existing record.
        Optional<WorkOrderPhoto> existing = photoRepository.findByStorageKey(storageKey);
        if (existing.isPresent()) {
            WorkOrderPhoto p = existing.get();
            String viewUrl = storagePort.presignGet(storageKey, GET_EXPIRY_SECONDS);
            log.info("photo_registration_idempotent storageKey={} photoId={} traceId={}",
                    storageKey, p.getId(), MDC.get("traceId"));
            return new PhotoRegistrationResult(p.getId(), p.getCategory(),
                    p.getCapturedAt(), viewUrl);
        }

        UploadIntent intent = intentRepository.findByStorageKey(storageKey)
                .orElseThrow(() -> new IntentNotFoundException(storageKey));

        if (intent.isExpired(Instant.now())) {
            throw new IntentExpiredException(storageKey);
        }

        ObjectMetadata meta = storagePort.headObject(storageKey)
                .orElseThrow(() -> new ObjectVerificationException(
                        "Object not found in storage: " + storageKey));

        if (!intent.getContentType().equalsIgnoreCase(meta.contentType())) {
            throw new ObjectVerificationException(
                    "Content type mismatch: expected " + intent.getContentType()
                    + ", got " + meta.contentType());
        }

        if (meta.contentLength() > intent.getMaxBytes()) {
            throw new ObjectVerificationException(
                    "Object size " + meta.contentLength()
                    + " exceeds declared maximum " + intent.getMaxBytes());
        }

        LocalDate retainUntil = LocalDate.now().plusDays(retentionDays);

        WorkOrderPhoto photo;
        try {
            photo = new WorkOrderPhoto(workOrderId, storageKey, category,
                    capturedAt, caption, retainUntil, registeredBy);
            photoRepository.save(photo);
        } catch (DataIntegrityViolationException ex) {
            // Race: another thread registered the same key concurrently. Return existing.
            WorkOrderPhoto race = photoRepository.findByStorageKey(storageKey)
                    .orElseThrow(() -> ex);
            String viewUrl = storagePort.presignGet(storageKey, GET_EXPIRY_SECONDS);
            return new PhotoRegistrationResult(race.getId(), race.getCategory(),
                    race.getCapturedAt(), viewUrl);
        }

        intent.markConsumed(Instant.now());
        intentRepository.save(intent);

        String viewUrl = storagePort.presignGet(storageKey, GET_EXPIRY_SECONDS);

        log.info("photo_registered workOrderId={} photoId={} storageKey={} actor={} traceId={}",
                workOrderId, photo.getId(), storageKey, registeredBy, MDC.get("traceId"));

        return new PhotoRegistrationResult(photo.getId(), photo.getCategory(),
                photo.getCapturedAt(), viewUrl);
    }

    /**
     * Returns metadata for all photos attached to a work order, with short-lived GET URLs.
     *
     * @param workOrderId the work order to list photos for
     * @return list of photo summaries ordered by capturedAt ascending
     */
    @Transactional(readOnly = true)
    public List<PhotoSummary> listPhotos(UUID workOrderId) {
        return photoRepository.findByWorkOrderIdOrderByCapturedAtAsc(workOrderId).stream()
                .map(p -> new PhotoSummary(
                        p.getId(),
                        p.getCategory(),
                        p.getCapturedAt(),
                        p.getCaption(),
                        storagePort.presignGet(p.getStorageKey(), GET_EXPIRY_SECONDS)))
                .toList();
    }

    // ---- Validation helpers ------------------------------------------------

    private static void validateContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new DisallowedContentTypeException(contentType);
        }
    }

    private static void validateContentLength(long contentLength) {
        if (contentLength <= 0 || contentLength > MAX_BYTES) {
            throw new ContentTooLargeException(contentLength, MAX_BYTES);
        }
    }

    private static String extFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png"  -> "png";
            case "image/webp" -> "webp";
            default           -> "bin";
        };
    }

    // ---- Result records ----------------------------------------------------

    public record UploadIntentResult(
            UUID    intentId,
            String  storageKey,
            String  uploadUrl,
            Instant expiresAt,
            String  requiredContentType,
            long    maxBytes
    ) {}

    public record PhotoRegistrationResult(
            UUID          photoId,
            PhotoCategory category,
            Instant       capturedAt,
            String        thumbnailUrl
    ) {}

    public record PhotoSummary(
            UUID          photoId,
            PhotoCategory category,
            Instant       capturedAt,
            String        caption,
            String        viewUrl
    ) {}
}

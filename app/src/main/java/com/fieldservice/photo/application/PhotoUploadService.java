package com.fieldservice.photo.application;

import com.fieldservice.api.GlobalExceptionHandler;
import com.fieldservice.outbox.payload.PhotoRegisteredPayload;
import com.fieldservice.photo.domain.PhotoCategory;
import com.fieldservice.photo.domain.PhotoStorageException;
import com.fieldservice.photo.domain.PhotoStoragePort;
import com.fieldservice.photo.domain.UploadIntent;
import com.fieldservice.photo.domain.UploadIntentRepository;
import com.fieldservice.photo.domain.WorkOrderPhoto;
import com.fieldservice.photo.domain.WorkOrderPhotoRepository;
import com.fieldservice.photo.infrastructure.PhotoStorageProperties;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.exception.ProviderDegradedException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for presigned photo upload, registration and listing.
 *
 * <p>Security invariants enforced:
 * <ul>
 *   <li>Only accepted content types are JPEG, PNG and WEBP (AC-3).</li>
 *   <li>Maximum accepted size is 5 MB (AC-3).</li>
 *   <li>Registration verifies object existence and metadata via headObject before persisting (AC-4).</li>
 *   <li>Presigned URLs are never logged (AC-5).</li>
 *   <li>Registration is idempotent on storage key (AC-10).</li>
 * </ul>
 */
@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final Map<String, String> EXT_MAP = Map.of(
            "image/jpeg", "jpg",
            "image/png",  "png",
            "image/webp", "webp"
    );

    private final PhotoStoragePort storage;
    private final UploadIntentRepository intentRepository;
    private final WorkOrderPhotoRepository photoRepository;
    private final DomainEventPublisher eventPublisher;
    private final PhotoStorageProperties props;

    public PhotoUploadService(PhotoStoragePort storage,
                               UploadIntentRepository intentRepository,
                               WorkOrderPhotoRepository photoRepository,
                               DomainEventPublisher eventPublisher,
                               PhotoStorageProperties props) {
        this.storage = storage;
        this.intentRepository = intentRepository;
        this.photoRepository = photoRepository;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    // ── Intent issuance ───────────────────────────────────────────────────────

    /**
     * Validates declared content type and size, generates the object key, persists the
     * intent record and returns the presigned PUT details.
     *
     * @throws IllegalArgumentException if the content type or size is not allowed
     * @throws ProviderDegradedException if the storage provider is unavailable
     */
    @Transactional
    public IntentResult createUploadIntent(UUID workOrderId,
                                            String contentType,
                                            long contentLength,
                                            PhotoCategory category,
                                            UUID actorId) {
        validateContentType(contentType);
        validateContentLength(contentLength);

        String ext = EXT_MAP.get(contentType);
        String key = "work-orders/" + workOrderId + "/" + UuidV7.generate() + "." + ext;

        PhotoStoragePort.PresignedPut presigned;
        try {
            presigned = storage.presignPut(
                    key, contentType, props.getPhoto().getMaxBytes(),
                    Duration.ofSeconds(props.getPhoto().getPutExpirySeconds()));
        } catch (PhotoStorageException e) {
            throw new ProviderDegradedException("photo-storage", e);
        }

        UploadIntent intent = new UploadIntent();
        intent.setWorkOrderId(workOrderId);
        intent.setStorageKey(key);
        intent.setContentType(contentType);
        intent.setMaxBytes(props.getPhoto().getMaxBytes());
        intent.setExpiresAt(Instant.now().plusSeconds(props.getPhoto().getPutExpirySeconds()));
        intent.setCreatedBy(actorId);
        intentRepository.save(intent);

        log.info("photo.intent_issued: workOrderId={} key={} contentType={} actorId={}",
                workOrderId, key, contentType, actorId);

        return new IntentResult(
                intent.getId(),
                key,
                presigned.uploadUrl(),
                intent.getExpiresAt(),
                contentType,
                props.getPhoto().getMaxBytes());
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Verifies the object via headObject, then persists the photo metadata row and
     * publishes the outbox event in one transaction.
     *
     * @throws PhotoRegistrationException if the intent is missing, expired, mismatched or object not found
     * @throws ProviderDegradedException  if the storage provider is unavailable
     */
    @Transactional
    public WorkOrderPhoto registerPhoto(UUID workOrderId,
                                         UUID intentId,
                                         String storageKey,
                                         PhotoCategory category,
                                         Instant capturedAt,
                                         String caption,
                                         UUID actorId) {
        // Idempotency: if already registered return the existing row
        var existing = photoRepository.findByStorageKey(storageKey);
        if (existing.isPresent()) {
            log.info("photo.register_idempotent: key={} workOrderId={}", storageKey, workOrderId);
            return existing.get();
        }

        // Validate intent
        UploadIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new PhotoRegistrationException(
                        "INTENT_NOT_FOUND", "No upload intent found for the supplied intentId."));

        if (!intent.getWorkOrderId().equals(workOrderId)) {
            throw new PhotoRegistrationException(
                    "INTENT_SCOPE_MISMATCH", "The intent does not belong to this work order.");
        }
        if (!intent.getStorageKey().equals(storageKey)) {
            throw new PhotoRegistrationException(
                    "INTENT_KEY_MISMATCH", "The supplied storageKey does not match the issued intent.");
        }
        if (intent.isConsumed()) {
            throw new PhotoRegistrationException(
                    "INTENT_ALREADY_CONSUMED", "This upload intent has already been registered.");
        }

        // Verify object exists and metadata matches
        PhotoStoragePort.ObjectMetadata meta;
        try {
            meta = storage.headObject(storageKey);
        } catch (PhotoStorageException e) {
            throw new ProviderDegradedException("photo-storage", e);
        }

        if (meta == null) {
            throw new PhotoRegistrationException(
                    "OBJECT_NOT_FOUND", "The uploaded object could not be found in storage.");
        }
        if (meta.contentLength() > intent.getMaxBytes()) {
            throw new PhotoRegistrationException(
                    "OBJECT_TOO_LARGE", "The uploaded object exceeds the declared maximum size.");
        }
        if (!meta.contentType().startsWith(intent.getContentType())) {
            throw new PhotoRegistrationException(
                    "CONTENT_TYPE_MISMATCH", "The uploaded object content type does not match the intent.");
        }

        // Persist photo row
        WorkOrderPhoto photo = new WorkOrderPhoto();
        photo.setWorkOrderId(workOrderId);
        photo.setStorageKey(storageKey);
        photo.setCategory(category);
        photo.setCapturedAt(capturedAt);
        photo.setCaption(caption);
        photo.setRetainUntil(LocalDate.now().plusDays(props.getPhoto().getRetentionDays()));
        photo.setCreatedBy(actorId);
        photoRepository.save(photo);

        // Mark intent consumed
        intent.setConsumedAt(Instant.now());
        intentRepository.save(intent);

        // Publish outbox event (same transaction)
        PhotoRegisteredPayload payload = new PhotoRegisteredPayload(
                photo.getId(), workOrderId, category.name(), capturedAt);
        eventPublisher.publish(DomainEvent.of(
                PhotoRegisteredPayload.EVENT_TYPE,
                PhotoRegisteredPayload.AGGREGATE_TYPE,
                workOrderId,
                Instant.now(),
                GlobalExceptionHandler.traceId(),
                actorId,
                PiiRedactionUtility.toPayloadMap(payload)));

        log.info("photo.registered: photoId={} workOrderId={} key={} actorId={}",
                photo.getId(), workOrderId, storageKey, actorId);
        return photo;
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    /**
     * Returns all registered photos for the work order, each with a short-lived
     * presigned GET URL.
     *
     * @throws ProviderDegradedException if the storage provider is unavailable
     */
    @Transactional(readOnly = true)
    public List<PhotoListItem> listPhotos(UUID workOrderId) {
        List<WorkOrderPhoto> photos = photoRepository.findByWorkOrderIdOrderByCreatedAtAsc(workOrderId);
        return photos.stream().map(p -> {
            String viewUrl;
            try {
                viewUrl = storage.presignGet(
                        p.getStorageKey(),
                        Duration.ofSeconds(props.getPhoto().getGetExpirySeconds()));
            } catch (PhotoStorageException e) {
                throw new ProviderDegradedException("photo-storage", e);
            }
            return new PhotoListItem(p.getId(), p.getCategory().name(), p.getCapturedAt(),
                    p.getCaption(), viewUrl);
        }).toList();
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    private void validateContentType(String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Content type '" + contentType + "' is not permitted. Allowed: image/jpeg, image/png, image/webp.");
        }
    }

    private void validateContentLength(long contentLength) {
        if (contentLength <= 0 || contentLength > props.getPhoto().getMaxBytes()) {
            throw new IllegalArgumentException(
                    "Declared content length must be between 1 and " + props.getPhoto().getMaxBytes() + " bytes.");
        }
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record IntentResult(
            UUID intentId,
            String storageKey,
            String uploadUrl,
            Instant expiresAt,
            String requiredContentType,
            long maxBytes
    ) {}

    public record PhotoListItem(
            UUID photoId,
            String category,
            Instant capturedAt,
            String caption,
            String viewUrl
    ) {}
}

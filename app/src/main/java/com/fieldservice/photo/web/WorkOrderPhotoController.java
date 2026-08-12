package com.fieldservice.photo.web;

import com.fieldservice.photo.application.PhotoUploadService;
import com.fieldservice.photo.application.PhotoUploadService.PhotoRegistrationResult;
import com.fieldservice.photo.application.PhotoUploadService.PhotoSummary;
import com.fieldservice.photo.application.PhotoUploadService.UploadIntentResult;
import com.fieldservice.photo.domain.PhotoCategory;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exposes the presigned direct-upload photo flow for work orders.
 *
 * <p>No image binary bytes are handled by this controller. The upload path is:
 * <ol>
 *   <li>POST /upload-intent — returns a presigned PUT URL; client uploads directly to storage</li>
 *   <li>POST / — registers metadata after the client PUT succeeded; verifies via HEAD</li>
 *   <li>GET / — returns photo metadata with short-lived presigned GET URLs</li>
 * </ol>
 *
 * <p>Presigned URLs are never included in logs or error response bodies.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{workOrderId}/photos")
public class WorkOrderPhotoController {

    private final PhotoUploadService       photoUploadService;
    private final RequestScopedAccessScope accessScope;

    public WorkOrderPhotoController(PhotoUploadService photoUploadService,
                                     RequestScopedAccessScope accessScope) {
        this.photoUploadService = photoUploadService;
        this.accessScope        = accessScope;
    }

    /**
     * Issues a presigned PUT intent for a work order photo.
     *
     * <p>Returns 201 with {intentId, storageKey, uploadUrl, expiresAt, requiredHeaders, maxBytes}.
     * The client must PUT the binary to uploadUrl using Content-Type from requiredHeaders.
     */
    @PostMapping("/upload-intent")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<UploadIntentResponse> uploadIntent(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody UploadIntentRequest request) {

        UUID actorId = accessScope.get().userId();
        UploadIntentResult result = photoUploadService.issueUploadIntent(
                workOrderId, request.contentType(), request.contentLength(), actorId);

        UploadIntentResponse body = new UploadIntentResponse(
                result.intentId(),
                result.storageKey(),
                result.uploadUrl(),
                result.expiresAt(),
                Map.of("Content-Type", result.requiredContentType()),
                result.maxBytes()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Registers photo metadata after the client has PUT the binary to storage.
     *
     * <p>Verifies the object exists via HEAD before persisting. Idempotent on storage key.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<PhotoRegistrationResponse> registerPhoto(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody RegisterPhotoRequest request) {

        UUID actorId = accessScope.get().userId();
        PhotoRegistrationResult result = photoUploadService.registerPhoto(
                workOrderId,
                request.storageKey(),
                request.category(),
                request.capturedAt(),
                request.caption(),
                actorId);

        PhotoRegistrationResponse body = new PhotoRegistrationResponse(
                result.photoId(),
                result.category(),
                result.capturedAt(),
                result.thumbnailUrl()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Returns metadata for all photos attached to the work order.
     *
     * <p>Each photo includes a short-lived presigned GET URL valid for 60 seconds.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<List<PhotoSummaryResponse>> listPhotos(
            @PathVariable UUID workOrderId) {

        List<PhotoSummary> summaries = photoUploadService.listPhotos(workOrderId);
        List<PhotoSummaryResponse> body = summaries.stream()
                .map(s -> new PhotoSummaryResponse(
                        s.photoId(),
                        s.category(),
                        s.capturedAt(),
                        s.caption(),
                        s.viewUrl()))
                .toList();
        return ResponseEntity.ok(body);
    }

    // ---- Request / response records ----------------------------------------

    /** Upload intent request body. */
    public record UploadIntentRequest(
            @NotBlank String contentType,
            long contentLength,
            @NotNull PhotoCategory category
    ) {}

    /** Upload intent response body. */
    public record UploadIntentResponse(
            UUID               intentId,
            String             storageKey,
            String             uploadUrl,
            Instant            expiresAt,
            Map<String, String> requiredHeaders,
            long               maxBytes
    ) {}

    /** Photo registration request body. */
    public record RegisterPhotoRequest(
            @NotBlank String storageKey,
            @NotNull  PhotoCategory category,
            @NotNull  Instant capturedAt,
            @Size(max = 500) String caption
    ) {}

    /** Photo registration response body. */
    public record PhotoRegistrationResponse(
            UUID          photoId,
            PhotoCategory category,
            Instant       capturedAt,
            String        thumbnailUrl
    ) {}

    /** Photo list item. */
    public record PhotoSummaryResponse(
            UUID          photoId,
            PhotoCategory category,
            Instant       capturedAt,
            String        caption,
            String        viewUrl
    ) {}
}

package com.fieldservice.photo.api;

import com.fieldservice.photo.application.PhotoRegistrationException;
import com.fieldservice.photo.application.PhotoUploadService;
import com.fieldservice.photo.domain.PhotoCategory;
import com.fieldservice.photo.domain.WorkOrderPhoto;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.security.AccessScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
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
 * Presigned direct photo upload endpoints for work order field evidence (WO-158).
 *
 * <p>Three operations:
 * <ol>
 *   <li>POST .../upload-intent — validate content type and size, return presigned PUT URL.</li>
 *   <li>POST .../ — verify object via HEAD, persist metadata in one transaction.</li>
 *   <li>GET .../ — list photos with short-lived presigned GET URLs.</li>
 * </ol>
 *
 * <p>Image binaries never pass through this controller. All presigned URLs are excluded
 * from error payloads and log output.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{workOrderId}/photos")
public class WorkOrderPhotoController {

    private final PhotoUploadService photoService;
    private final AccessScopeResolver scopeResolver;

    public WorkOrderPhotoController(PhotoUploadService photoService,
                                     AccessScopeResolver scopeResolver) {
        this.photoService = photoService;
        this.scopeResolver = scopeResolver;
    }

    // ── POST .../upload-intent ────────────────────────────────────────────────

    @PostMapping("/upload-intent")
    @PreAuthorize("hasAnyAuthority('ROLE_TECHNICIAN','ROLE_DISPATCHER','ROLE_ADMIN')")
    public ResponseEntity<?> createUploadIntent(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody UploadIntentRequest request) {

        UUID actorId = scopeResolver.resolve().userId();
        PhotoUploadService.IntentResult result = photoService.createUploadIntent(
                workOrderId,
                request.contentType(),
                request.contentLength(),
                PhotoCategory.valueOf(request.category()),
                actorId);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "data", Map.of(
                        "intentId", result.intentId(),
                        "storageKey", result.storageKey(),
                        "uploadUrl", result.uploadUrl(),
                        "expiresAt", result.expiresAt(),
                        "requiredHeaders", Map.of("Content-Type", result.requiredContentType()),
                        "maxBytes", result.maxBytes()
                )
        ));
    }

    // ── POST .../ ─────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_TECHNICIAN','ROLE_DISPATCHER','ROLE_ADMIN')")
    public ResponseEntity<?> registerPhoto(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody RegisterPhotoRequest request,
            HttpServletRequest httpRequest) {

        UUID actorId = scopeResolver.resolve().userId();
        WorkOrderPhoto photo;
        try {
            photo = photoService.registerPhoto(
                    workOrderId,
                    request.intentId(),
                    request.storageKey(),
                    PhotoCategory.valueOf(request.category()),
                    request.capturedAt(),
                    request.caption(),
                    actorId);
        } catch (PhotoRegistrationException e) {
            String tid = MDC.get("traceId") != null ? MDC.get("traceId") : "none";
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorEnvelope(
                            e.getCode(),
                            e.getMessage(),
                            List.of(),
                            tid,
                            Instant.now()));
        }

        String viewUrl = null;
        try {
            List<PhotoUploadService.PhotoListItem> items = photoService.listPhotos(workOrderId);
            viewUrl = items.stream()
                    .filter(i -> i.photoId().equals(photo.getId()))
                    .map(PhotoUploadService.PhotoListItem::viewUrl)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            // view URL is optional in the registration response
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "data", Map.of(
                        "photoId", photo.getId(),
                        "category", photo.getCategory().name(),
                        "capturedAt", photo.getCapturedAt(),
                        "thumbnailUrl", viewUrl != null ? viewUrl : ""
                )
        ));
    }

    // ── GET .../ ──────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> listPhotos(@PathVariable UUID workOrderId) {
        List<PhotoUploadService.PhotoListItem> items = photoService.listPhotos(workOrderId);
        return ResponseEntity.ok(Map.of("data", items));
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record UploadIntentRequest(
            @NotBlank String contentType,
            @Positive long contentLength,
            @NotBlank String category
    ) {}

    public record RegisterPhotoRequest(
            @NotNull UUID intentId,
            @NotBlank String storageKey,
            @NotBlank String category,
            @NotNull Instant capturedAt,
            @Size(max = 500) String caption
    ) {}
}

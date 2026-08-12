package com.fieldservice.photoanalysis.api;

import com.fieldservice.photoanalysis.internal.PhotoAnalysisService;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.AnalysisResult;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.FeatureDisabledException;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.OverrideResult;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.PhotoAccessDeniedException;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.PhotoNotFoundException;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.PhotoValidationException;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * Exposes the AI-assisted photo analysis endpoints.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>POST /api/v1/work-orders/{id}/photos/{photoId}/analysis — analyse a stored photo
 *       and return an attributed draft description.</li>
 *   <li>POST /api/v1/work-orders/{id}/photos/{photoId}/description — record the technician's
 *       final description and compute the override classification.</li>
 * </ul>
 *
 * <p>The analysis endpoint never accepts a URL from the caller (SSRF prevention).
 * Model output is returned as a draft DTO only; it is never persisted as the description
 * without the technician's explicit action via the /description endpoint.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{workOrderId}/photos/{photoId}")
public class PhotoAnalysisController {

    private final PhotoAnalysisService      service;
    private final RequestScopedAccessScope  accessScope;

    public PhotoAnalysisController(PhotoAnalysisService service,
                                    RequestScopedAccessScope accessScope) {
        this.service     = service;
        this.accessScope = accessScope;
    }

    /**
     * Analyses a stored photo and returns an AI-attributed draft description.
     *
     * <p>Returns 200 with the draft on success, 503 when the feature flag is off or the
     * AI provider is unavailable.
     */
    @PostMapping("/analysis")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<AnalysisResponse> analysePhoto(
            @PathVariable UUID workOrderId,
            @PathVariable UUID photoId,
            @Valid @RequestBody(required = false) AnalysisRequest request) {

        boolean includeFaultContext = request != null && Boolean.TRUE.equals(request.includeFaultContext());

        AnalysisResult result = service.analyse(workOrderId, photoId,
                includeFaultContext, accessScope.get());

        if (result.degraded()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(AnalysisResponse.degraded(result.interactionId()));
        }

        return ResponseEntity.ok(AnalysisResponse.success(result));
    }

    /**
     * Records the technician's final description and the override classification.
     *
     * <p>The description is the authoritative text that will be persisted — this endpoint
     * never auto-submits the AI suggestion.
     */
    @PostMapping("/description")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<DescriptionResponse> recordDescription(
            @PathVariable UUID workOrderId,
            @PathVariable UUID photoId,
            @Valid @RequestBody DescriptionRequest request) {

        OverrideResult result = service.recordDescription(
                workOrderId, photoId,
                request.description(),
                request.suggestionInteractionId(),
                accessScope.get());

        return ResponseEntity.ok(new DescriptionResponse(
                result.finalDescription(),
                result.overrideClassification(),
                result.similarityScore()));
    }

    // ── Request / response records ─────────────────────────────────────────────

    public record AnalysisRequest(Boolean includeFaultContext) {}

    public record AnalysisResponse(
            UUID     interactionId,
            String   suggestedDescription,
            boolean  advisory,
            String   source,
            String   provider,
            String[] basis,
            boolean  degraded,
            String   degradedCode) {

        static AnalysisResponse success(AnalysisResult result) {
            return new AnalysisResponse(
                    result.interactionId(),
                    result.suggestedDescription(),
                    true,
                    "AI",
                    result.provider(),
                    result.basis(),
                    false,
                    null);
        }

        static AnalysisResponse degraded(UUID interactionId) {
            return new AnalysisResponse(
                    interactionId,
                    null,
                    false,
                    null,
                    null,
                    null,
                    true,
                    "AI_PROVIDER_UNAVAILABLE");
        }
    }

    public record DescriptionRequest(
            @Size(max = 2000) String description,
            UUID suggestionInteractionId
    ) {}

    public record DescriptionResponse(
            String               description,
            String               overrideClassification,
            java.math.BigDecimal similarityScore
    ) {}

    // ── Exception handlers ────────────────────────────────────────────────────

    @RestControllerAdvice(assignableTypes = PhotoAnalysisController.class)
    static class PhotoAnalysisExceptionHandler {

        @ExceptionHandler(PhotoNotFoundException.class)
        public ResponseEntity<Map<String, Object>> notFound(PhotoNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "code", "PHOTO_NOT_FOUND",
                            "message", "Photo not found"));
        }

        @ExceptionHandler(PhotoAccessDeniedException.class)
        public ResponseEntity<Map<String, Object>> accessDenied(PhotoAccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", 403, "code", "PHOTO_ACCESS_DENIED",
                            "message", "Access denied"));
        }

        @ExceptionHandler(PhotoValidationException.class)
        public ResponseEntity<Map<String, Object>> validationFailed(PhotoValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", 400, "code", "PHOTO_INVALID_CONTENT",
                            "message", "Photo content type not supported"));
        }

        @ExceptionHandler(FeatureDisabledException.class)
        public ResponseEntity<Map<String, Object>> featureDisabled(FeatureDisabledException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", 503, "code", "AI_FEATURE_DISABLED",
                            "message", "Photo analysis is currently unavailable",
                            "degraded", true));
        }
    }
}

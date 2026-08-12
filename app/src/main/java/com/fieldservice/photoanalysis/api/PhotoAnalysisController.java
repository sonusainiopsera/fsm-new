package com.fieldservice.photoanalysis.api;

import com.fieldservice.photoanalysis.internal.PhotoAnalysisService;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.AnalysisDraft;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.DescriptionResult;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.DegradedAnalysisException;
import com.fieldservice.photoanalysis.internal.PhotoAnalysisService.PhotoAnalysisDisabledException;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for AI-assisted photo analysis (WO-181).
 *
 * <pre>
 *   POST /api/v1/work-orders/{workOrderId}/photos/{photoId}/analysis
 *        → trigger AI analysis; returns advisory draft, never auto-persists description
 *   POST /api/v1/work-orders/{workOrderId}/photos/{photoId}/description
 *        → record technician's final description choice + override classification
 * </pre>
 *
 * <h3>Security</h3>
 * Both endpoints require TECHNICIAN, DISPATCHER, or ADMIN authority.
 * The service layer enforces per-work-order access scope.
 *
 * <h3>Idempotency</h3>
 * The analysis endpoint accepts an optional {@code Idempotency-Key} header (UUID).
 * The description endpoint is idempotent on (photoId, interactionId).
 */
@RestController
@RequestMapping("/api/v1/work-orders/{workOrderId}/photos/{photoId}")
public class PhotoAnalysisController {

    private final PhotoAnalysisService service;
    private final AccessScopeResolver  scopeResolver;

    public PhotoAnalysisController(PhotoAnalysisService service, AccessScopeResolver scopeResolver) {
        this.service       = service;
        this.scopeResolver = scopeResolver;
    }

    // ── POST .../analysis ─────────────────────────────────────────────────────

    @PostMapping("/analysis")
    @PreAuthorize("hasAnyAuthority('ROLE_TECHNICIAN','ROLE_DISPATCHER','ROLE_ADMIN')")
    public ResponseEntity<?> analyzePhoto(
            @PathVariable UUID workOrderId,
            @PathVariable UUID photoId,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
            @RequestBody @Valid AnalyzePhotoRequest body) {

        AccessScope scope = scopeResolver.resolve();

        AnalysisDraft draft = service.analyzePhoto(
                workOrderId, photoId, scope,
                body.includeFaultContext(), body.faultContext());

        // Never render model output as HTML; return as plain JSON string (AC-9)
        return ResponseEntity.ok(Map.of(
                "interactionId",        draft.interactionId(),
                "suggestedDescription", draft.suggestedDescription() != null
                                                ? draft.suggestedDescription() : "",
                "source",     draft.source(),
                "advisory",   draft.advisory(),
                "provider",   draft.provider()
        ));
    }

    // ── POST .../description ─────────────────────────────────────────────────

    @PostMapping("/description")
    @PreAuthorize("hasAnyAuthority('ROLE_TECHNICIAN','ROLE_DISPATCHER','ROLE_ADMIN')")
    public ResponseEntity<?> recordDescription(
            @PathVariable UUID workOrderId,
            @PathVariable UUID photoId,
            @RequestBody @Valid RecordDescriptionRequest body) {

        AccessScope scope = scopeResolver.resolve();

        DescriptionResult result = service.recordDescription(
                workOrderId, photoId, scope,
                body.description(), body.suggestionInteractionId());

        return ResponseEntity.ok(Map.of(
                "overrideClassification", result.overrideClassification(),
                "similarityScore",        result.similarityScore()
        ));
    }

    // ── Exception handlers ───────────────────────────────────────────────────

    @ExceptionHandler(PhotoAnalysisDisabledException.class)
    public ResponseEntity<ErrorEnvelope> handleDisabled(PhotoAnalysisDisabledException ex) {
        return ResponseEntity.status(503)
                .body(new ErrorEnvelope("FEATURE_DISABLED", ex.getMessage(), null, Instant.now()));
    }

    @ExceptionHandler(DegradedAnalysisException.class)
    public ResponseEntity<ErrorEnvelope> handleDegraded(DegradedAnalysisException ex) {
        return ResponseEntity.status(503)
                .body(new ErrorEnvelope(ex.getCode(), ex.getMessage(), null, Instant.now()));
    }

    // ── Request records ──────────────────────────────────────────────────────

    /**
     * Request body for photo analysis.
     *
     * @param includeFaultContext  whether to include fault context in the vision prompt
     * @param faultContext         optional fault/asset description; PII-scrubbed server-side
     */
    public record AnalyzePhotoRequest(
            boolean includeFaultContext,
            @Size(max = 1000) String faultContext
    ) {}

    /**
     * Request body for recording the technician's final description.
     *
     * @param description              technician's final text (blank = discard AI suggestion)
     * @param suggestionInteractionId  interaction ID from the preceding analysis call, or null
     */
    public record RecordDescriptionRequest(
            @Size(max = 2000) String description,
            UUID suggestionInteractionId
    ) {}
}

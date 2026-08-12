package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.FieldCorrection;
import com.fieldservice.privacy.api.SubjectErasureView;
import com.fieldservice.privacy.internal.RectificationService;
import com.fieldservice.privacy.internal.SubjectErasureService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API for subject rights operations: rectification and erasure.
 *
 * <p>All endpoints restricted to {@code PRIVACY_ADMIN} and {@code ADMIN} roles.
 * Erasure is asynchronous — POST returns 202 and the worker job executes erasure.
 */
@RestController
@RequestMapping("/api/v1/privacy")
public class SubjectRightsController {

    private final RectificationService   rectificationService;
    private final SubjectErasureService  erasureService;

    public SubjectRightsController(RectificationService rectificationService,
                                    SubjectErasureService erasureService) {
        this.rectificationService = rectificationService;
        this.erasureService       = erasureService;
    }

    /**
     * Applies allow-listed field-level corrections to a subject's personal data.
     *
     * <p>Returns 200 with applied and skipped lists.  Returns 400 if any field is not
     * on the allow-list.  Returns 422 if the authorising DSAR is not VERIFIED.
     */
    @PostMapping("/subjects/{subjectType}/{subjectId}/rectifications")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<RectificationResponse> rectify(
            @PathVariable String subjectType,
            @PathVariable UUID subjectId,
            @Valid @RequestBody RectificationRequest request,
            Authentication authentication) {

        String actor = actorName(authentication);

        List<FieldCorrection> corrections = request.corrections().stream()
                .map(c -> new FieldCorrection(c.entityName(), c.fieldName(), c.newValue()))
                .toList();

        RectificationService.RectificationOutcome outcome =
                rectificationService.rectify(request.dsarRequestId(), corrections, request.note(), actor);

        return ResponseEntity.ok(RectificationResponse.from(outcome.applied(), outcome.skipped()));
    }

    /**
     * Initiates asynchronous cryptographic erasure of a subject's personal data.
     *
     * <p>Returns 202 Accepted — actual erasure runs on the worker profile.
     * Returns 422 if the DSAR is not in state VERIFIED, if a legal hold applies,
     * or if confirmation is missing.
     */
    @PostMapping("/subjects/{subjectType}/{subjectId}/erasure")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<ErasureResponse> initiateErasure(
            @PathVariable String subjectType,
            @PathVariable UUID subjectId,
            @Valid @RequestBody ErasureRequest request,
            Authentication authentication) {

        String actor = actorName(authentication);

        UUID erasureId = erasureService.initiateErasure(
                request.dsarRequestId(), request.confirmation(), request.note(), actor);

        return ResponseEntity.accepted()
                .body(new ErasureResponse(erasureId, "PENDING"));
    }

    /**
     * Returns the erasure tombstone for a completed erasure.
     *
     * <p>Returns 404 if no tombstone exists for the given id.
     */
    @GetMapping("/erasures/{id}")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<SubjectErasureResponse> getErasure(
            @PathVariable UUID id) {

        SubjectErasureView view = erasureService.getErasure(id);
        return ResponseEntity.ok(SubjectErasureResponse.from(view));
    }

    private static String actorName(Authentication authentication) {
        return authentication != null ? authentication.getName() : "SYSTEM";
    }
}

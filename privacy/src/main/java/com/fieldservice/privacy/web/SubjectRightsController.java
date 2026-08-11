package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.ErasureView;
import com.fieldservice.privacy.api.InitiateErasureRequest;
import com.fieldservice.privacy.api.RectifyRequest;
import com.fieldservice.privacy.api.RectifyResponse;
import com.fieldservice.privacy.api.SubjectRightsPort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST endpoints for subject rights: rectification and cryptographic erasure.
 *
 * <p>All endpoints require {@code PRIVACY_ADMIN} or {@code ADMIN} role;
 * method security is enforced in the service layer via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/privacy")
public class SubjectRightsController {

    private final SubjectRightsPort rightsPort;

    public SubjectRightsController(SubjectRightsPort rightsPort) {
        this.rightsPort = rightsPort;
    }

    /**
     * Apply allow-listed field corrections to a subject's personal data.
     * Returns 200 with the list of applied and skipped corrections.
     */
    @PostMapping("/subjects/{subjectType}/{subjectId}/rectifications")
    public RectifyResponse rectify(
            @PathVariable String subjectType,
            @PathVariable UUID subjectId,
            @Valid @RequestBody RectifyRequest body) {
        return rightsPort.rectify(subjectType, subjectId, body);
    }

    /**
     * Initiate cryptographic erasure for a subject.
     * Returns 202 Accepted with the erasure tombstone reference.
     * Erasure is idempotent: re-submission returns the existing tombstone.
     */
    @PostMapping("/subjects/{subjectType}/{subjectId}/erasure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ErasureView initiateErasure(
            @PathVariable String subjectType,
            @PathVariable UUID subjectId,
            @Valid @RequestBody InitiateErasureRequest body) {
        return rightsPort.initiateErasure(subjectType, subjectId, body);
    }

    /**
     * Retrieve an erasure tombstone by ID.
     * Returns 200 with the full tombstone view including verification results.
     */
    @GetMapping("/erasures/{id}")
    public ErasureView getErasure(@PathVariable UUID id) {
        return rightsPort.getErasure(id);
    }
}

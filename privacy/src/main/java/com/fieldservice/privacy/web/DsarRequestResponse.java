package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarState;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON response body for DSAR request endpoints.
 */
public record DsarRequestResponse(
        UUID            id,
        DsarRequestType requestType,
        String          subjectType,
        UUID            subjectId,
        DsarState       state,
        Instant         submittedAt,
        Instant         dueAt,
        Instant         identityVerifiedAt,
        String          verificationMethod,
        UUID            assignedHandler,
        String          outcome,
        String          outcomeNote,
        int             attemptCount,
        long            remainingDays,
        boolean         atRisk,
        Instant         createdAt,
        String          createdBy,
        Instant         updatedAt,
        String          updatedBy,
        int             version
) {
    static DsarRequestResponse from(DsarRequestView view) {
        return new DsarRequestResponse(
                view.id(), view.requestType(), view.subjectType(), view.subjectId(),
                view.state(), view.submittedAt(), view.dueAt(),
                view.identityVerifiedAt(), view.verificationMethod(),
                view.assignedHandler(), view.outcome(), view.outcomeNote(),
                view.attemptCount(), view.remainingDays(), view.atRisk(),
                view.createdAt(), view.createdBy(), view.updatedAt(), view.updatedBy(),
                view.version());
    }
}

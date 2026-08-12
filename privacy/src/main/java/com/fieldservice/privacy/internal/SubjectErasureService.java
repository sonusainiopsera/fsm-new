package com.fieldservice.privacy.internal;

import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.SubjectErasureView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read side and request-initiation facade for subject erasure.
 *
 * <p>Erasure execution runs asynchronously via {@link SubjectErasureJob}.  This service
 * validates the authorising DSAR request and returns a view for the controller.
 */
@Service
@Transactional(readOnly = true)
class SubjectErasureService {

    private final DsarRequestRepository  requestRepository;
    private final SubjectErasureRepository erasureRepository;
    private final ObjectMapper           objectMapper;

    SubjectErasureService(DsarRequestRepository  requestRepository,
                          SubjectErasureRepository erasureRepository,
                          ObjectMapper           objectMapper) {
        this.requestRepository  = requestRepository;
        this.erasureRepository  = erasureRepository;
        this.objectMapper       = objectMapper;
    }

    /**
     * Validates the authorising DSAR and returns the erasure job state.
     *
     * <p>Returns the erasure ID that the worker job will use when persisting the tombstone.
     * The actual erasure runs asynchronously on the worker profile.
     *
     * @return a placeholder erasureId (the DSAR request id) for the 202 response
     */
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    UUID initiateErasure(UUID dsarRequestId, String confirmation, String note, String actor) {
        DsarRequestEntity request = requestRepository.findById(dsarRequestId)
                .orElseThrow(() -> new NotFoundException("DsarRequest", dsarRequestId.toString()));

        if (request.getRequestType() != DsarRequestType.ERASURE) {
            throw new BusinessGuardException("WRONG_REQUEST_TYPE",
                    "DSAR request " + dsarRequestId + " is not of type ERASURE"
                    + " (type: " + request.getRequestType() + ")");
        }

        if (request.getState() != DsarState.VERIFIED) {
            throw new BusinessGuardException("DSAR_NOT_VERIFIED",
                    "DSAR request " + dsarRequestId + " must be in state VERIFIED to authorise erasure"
                    + " (current state: " + request.getState() + ")");
        }

        // Check for in-flight or completed erasure for this subject
        boolean alreadyCompleted = erasureRepository
                .findCompletedBySubject(request.getSubjectType(), request.getSubjectId())
                .isPresent();
        if (alreadyCompleted) {
            throw new ConflictException("Subject " + request.getSubjectType()
                    + "/" + request.getSubjectId() + " has already been erased");
        }

        if (!"CONFIRMED".equals(confirmation)) {
            throw new BusinessGuardException("CONFIRMATION_REQUIRED",
                    "Erasure requires explicit confirmation value 'CONFIRMED'");
        }

        return dsarRequestId;
    }

    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    SubjectErasureView getErasure(UUID id) {
        SubjectErasureEntity entity = erasureRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SubjectErasure", id.toString()));
        return entity.toView(objectMapper);
    }
}

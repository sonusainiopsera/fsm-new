package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ErasureView;
import com.fieldservice.privacy.api.InitiateErasureRequest;
import com.fieldservice.privacy.api.RectifyRequest;
import com.fieldservice.privacy.api.RectifyResponse;
import com.fieldservice.privacy.api.SubjectRightsPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implements {@link SubjectRightsPort} by delegating to the internal service components.
 * Package-private — callers use the port interface only.
 */
@Service
@Transactional(readOnly = true)
class SubjectRightsServiceImpl implements SubjectRightsPort {

    private final RectificationService rectificationService;
    private final SubjectErasureJob erasureJob;

    SubjectRightsServiceImpl(RectificationService rectificationService,
                              SubjectErasureJob erasureJob) {
        this.rectificationService = rectificationService;
        this.erasureJob           = erasureJob;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public RectifyResponse rectify(String subjectType, UUID subjectId, RectifyRequest req) {
        return rectificationService.rectify(subjectType, subjectId, req);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ErasureView initiateErasure(String subjectType, UUID subjectId, InitiateErasureRequest req) {
        return erasureJob.initiateErasure(subjectType, subjectId, req);
    }

    @Override
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ErasureView getErasure(UUID erasureId) {
        return erasureJob.getErasure(erasureId);
    }
}

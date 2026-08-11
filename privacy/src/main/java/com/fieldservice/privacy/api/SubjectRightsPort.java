package com.fieldservice.privacy.api;

import java.util.UUID;

/**
 * Public port for subject rights operations — rectification and cryptographic erasure.
 *
 * <p>All methods require {@code PRIVACY_ADMIN} or {@code ADMIN} role.
 */
public interface SubjectRightsPort {

    /**
     * Applies allow-listed field corrections to a subject's personal data.
     *
     * <p>Requires an authorising DSAR request in state {@code VERIFIED} or later.
     * All corrections are applied in a single transaction; a partial write is not possible.
     *
     * @param subjectType  subject type (e.g. {@code "CUSTOMER"})
     * @param subjectId    subject UUID
     * @param req          corrections to apply
     * @return applied and skipped corrections
     * @throws com.fieldservice.platform.exception.BusinessGuardException (422) for guard refusals
     * @throws com.fieldservice.platform.exception.NotFoundException      (404) if the DSAR request does not exist
     */
    RectifyResponse rectify(String subjectType, UUID subjectId, RectifyRequest req);

    /**
     * Initiates cryptographic erasure for the subject.
     *
     * <p>Erasure is asynchronous and idempotent. Returns 202 with a tombstone reference.
     *
     * @param subjectType  subject type
     * @param subjectId    subject UUID
     * @param req          erasure request with authorising DSAR reference and confirmation token
     * @return tombstone view with initial state
     * @throws com.fieldservice.platform.exception.BusinessGuardException (422) for guard refusals
     */
    ErasureView initiateErasure(String subjectType, UUID subjectId, InitiateErasureRequest req);

    /**
     * Returns the erasure tombstone by its ID.
     *
     * @throws com.fieldservice.platform.exception.NotFoundException (404) if not found
     */
    ErasureView getErasure(UUID erasureId);
}

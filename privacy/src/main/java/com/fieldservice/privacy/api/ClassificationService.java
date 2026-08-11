package com.fieldservice.privacy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Full service contract for the classification registry, extending read operations
 * with the admin mutation and paginated listing used by the controller layer.
 */
public interface ClassificationService extends ClassificationRegistry {

    /**
     * Returns a page of classification rows, optionally filtered by tier.
     *
     * @param pageable  pagination and sort parameters (size already clamped to ≤ 50 by the caller)
     * @param tierFilter optional tier filter; null returns all tiers
     */
    Page<ClassificationView> listPage(Pageable pageable, ClassificationTier tierFilter);

    /**
     * Updates an existing classification row.
     *
     * <p>Must be called inside an active transaction so the Envers revision and outbox
     * event are written atomically with the tier change.
     *
     * @param id              registry row primary key
     * @param tier            new tier value
     * @param lawfulBasisNote updated lawful-basis note (may be null to clear)
     * @param handlingNotes   updated handling notes (may be null to clear)
     * @param expectedVersion client-side optimistic-lock version; 409 if it differs from DB
     * @param updatedBy       actor identifier for the audit trail (user UUID string)
     * @return the updated view
     * @throws com.fieldservice.platform.api.exception.NotFoundException if {@code id} is unknown
     * @throws com.fieldservice.platform.api.exception.ConflictException if {@code expectedVersion}
     *         does not match the current row version
     */
    ClassificationView update(UUID id,
                               ClassificationTier tier,
                               String lawfulBasisNote,
                               String handlingNotes,
                               int expectedVersion,
                               String updatedBy);
}

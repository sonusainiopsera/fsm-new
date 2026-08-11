package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON response body for classification endpoints.
 *
 * <p>Mapped from {@link ClassificationView}; declared separately so the controller
 * layer can evolve the API shape without touching the domain view.
 */
public record ClassificationResponse(
        UUID               id,
        String             module,
        String             entityName,
        String             fieldName,
        ClassificationTier tier,
        String             lawfulBasisNote,
        String             handlingNotes,
        Instant            updatedAt,
        String             updatedBy,
        int                version
) {
    static ClassificationResponse from(ClassificationView view) {
        return new ClassificationResponse(
                view.id(), view.module(), view.entityName(), view.fieldName(),
                view.tier(), view.lawfulBasisNote(), view.handlingNotes(),
                view.updatedAt(), view.updatedBy(), view.version());
    }
}

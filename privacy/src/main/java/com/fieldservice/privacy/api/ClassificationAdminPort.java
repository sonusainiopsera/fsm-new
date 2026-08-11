package com.fieldservice.privacy.api;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Admin operations on the data classification registry.
 *
 * <p>Separated from the read-only {@link ClassificationRegistry} to keep the public
 * read port narrow and side-effect-free. The admin port is the entry point for the
 * {@code ClassificationController} — the controller must never reference the internal
 * repository or entity directly.
 */
public interface ClassificationAdminPort {

    /**
     * Returns a paginated list of classification rows, optionally filtered by tier.
     * Page size is server-enforced to a maximum of 50. Sort is allow-listed and
     * tie-broken on the row UUID.
     */
    PagedResponse<ClassificationView> listClassifications(
            @Nullable ClassificationTier tierFilter,
            PageQuery pageQuery,
            HttpServletRequest request);

    /**
     * Updates the tier, lawful-basis note and handling notes for a single row.
     *
     * <p>Implements optimistic locking: the caller must supply the current {@code version}
     * value. If the row has been modified since the caller last read it, the method throws
     * {@link com.fieldservice.platform.exception.ConflictException} (→ HTTP 409).
     *
     * <p>Publishes a {@code ClassificationChanged} outbox event in the same transaction.
     * Creates a Hibernate Envers revision on the audit table.
     *
     * @param id      the classification row UUID
     * @param request the update payload
     * @return the updated view
     * @throws com.fieldservice.platform.exception.NotFoundException if {@code id} is unknown
     * @throws com.fieldservice.platform.exception.ConflictException if version is stale
     */
    ClassificationView updateClassification(UUID id, UpdateClassificationRequest request);
}

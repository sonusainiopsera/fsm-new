package com.fieldservice.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only query surface for Envers revision history.
 *
 * <p>Restricted to ADMIN and COMPLIANCE_REVIEWER roles. No method in this interface
 * or any implementing class may save, update, or delete a revision row.
 *
 * <p>Entity type inputs are validated against an allow-list inside the implementation;
 * an unknown entity type returns 400, not an error or empty result.
 */
public interface AuditRevisionQueryService {

    /**
     * Search revision history across all audited entity types or filtered to one.
     *
     * @param filter  search filter
     * @param page    zero-based page index
     * @param size    requested page size, capped server-side at {@code MAX_PAGE_SIZE}
     * @return page of revision entries ordered by (rev_timestamp desc, rev desc)
     */
    RevisionPage search(RevisionFilter filter, int page, int size);

    /**
     * Returns the field-level before/after diff for a single revision.
     *
     * @param revisionNumber Envers revision number
     * @param entityType     allow-listed entity type name
     * @param entityId       UUID of the audited entity
     * @return revision detail with diff, or empty if not found
     */
    java.util.Optional<RevisionDetail> getRevisionDiff(int revisionNumber, String entityType, UUID entityId);

    int MAX_PAGE_SIZE = 50;

    // ── Result types ────────────────────────────────────────────────────────────

    record RevisionFilter(
            String entityType,
            UUID entityId,
            String actorUserId,
            Instant from,
            Instant to,
            Integer revFrom,
            Integer revTo
    ) {}

    record RevisionEntry(
            int revisionNumber,
            Instant revisionTimestamp,
            String actor,
            String entityType,
            UUID entityId,
            String changeType,
            List<String> changedFields
    ) {}

    record RevisionPage(
            List<RevisionEntry> data,
            PageMeta page,
            boolean hasNext
    ) {}

    record PageMeta(int number, int size, long totalElements) {}

    record RevisionDetail(
            int revisionNumber,
            Instant revisionTimestamp,
            String actor,
            String entityType,
            UUID entityId,
            List<FieldDiff> fields
    ) {}

    record FieldDiff(
            String name,
            Object before,
            Object after,
            boolean changed,
            boolean masked
    ) {}
}

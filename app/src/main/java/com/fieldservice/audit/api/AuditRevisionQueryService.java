package com.fieldservice.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read-only interface for searching and inspecting Hibernate Envers revision history.
 *
 * <p>All search operations are strictly read-only; no implementation may issue a write,
 * update or delete against any audit table.
 *
 * <p>Entity type inputs are validated against an internal allow-list before any query is
 * executed, so unknown or injection-attempt values are rejected with 400 field errors.
 */
public interface AuditRevisionQueryService {

    /**
     * Search revision history with optional filters.
     *
     * @param filter  filter criteria (all fields optional)
     * @param cursor  keyset cursor from a previous page, or null for the first page
     * @param size    page size, clamped to {@code [1, 50]}
     * @return paged revision summaries in stable (revTimestamp desc, rev desc) order
     * @throws InvalidAuditFilterException if entityType or sortField is not allow-listed
     */
    RevisionPage search(RevisionFilter filter, RevisionCursor cursor, int size);

    /**
     * Returns a single revision with a field-level before/after diff.
     *
     * @param revisionNumber  the Envers revision number
     * @param entityType      allow-listed entity type name (required)
     * @param entityId        entity UUID (required)
     * @return populated revision detail, or empty if no such revision exists
     * @throws InvalidAuditFilterException if entityType is not allow-listed
     */
    Optional<RevisionDetail> getDetail(int revisionNumber, String entityType, UUID entityId);

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────

    record RevisionFilter(
            String   entityType,
            UUID     entityId,
            String   actorId,
            Instant  from,
            Instant  to,
            Integer  revFrom,
            Integer  revTo) {

        public static RevisionFilter empty() {
            return new RevisionFilter(null, null, null, null, null, null, null);
        }
    }

    record RevisionCursor(long revTimestampMillis, int rev) {}

    record RevisionPage(
            List<RevisionSummary> data,
            RevisionCursor        nextCursor,
            boolean               hasMore) {}

    record RevisionSummary(
            int      revisionNumber,
            Instant  revisionTimestamp,
            String   actorUserId,
            String   actorRole,
            String   entityType,
            UUID     entityId,
            String   changeType,
            List<String> changedFieldNames) {}

    record RevisionDetail(
            int              revisionNumber,
            Instant          revisionTimestamp,
            String           actorUserId,
            String           actorRole,
            String           entityType,
            UUID             entityId,
            List<FieldDiff>  fields) {}

    record FieldDiff(
            String  name,
            String  before,
            String  after,
            boolean changed,
            boolean masked) {}

    class InvalidAuditFilterException extends RuntimeException {
        private final String fieldName;
        private final String rejectedValue;

        public InvalidAuditFilterException(String fieldName, String rejectedValue) {
            super("Invalid audit filter field '" + fieldName + "': rejected value '" + rejectedValue + "'");
            this.fieldName     = fieldName;
            this.rejectedValue = rejectedValue;
        }

        public String getFieldName()     { return fieldName; }
        public String getRejectedValue() { return rejectedValue; }
    }
}

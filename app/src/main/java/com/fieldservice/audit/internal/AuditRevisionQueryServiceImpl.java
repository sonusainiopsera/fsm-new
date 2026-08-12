package com.fieldservice.audit.internal;

import com.fieldservice.audit.api.AuditRevisionQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only implementation of {@link AuditRevisionQueryService}.
 *
 * <p>All entity type and sort field inputs are validated against the allow-list before
 * any query is constructed. No method in this class issues a write or delete against
 * any audit table.
 */
@Service
class AuditRevisionQueryServiceImpl implements AuditRevisionQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditRevisionQueryServiceImpl.class);

    private final AuditRevisionRepository repository;

    AuditRevisionQueryServiceImpl(AuditRevisionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public RevisionPage search(RevisionFilter filter, RevisionCursor cursor, int size) {
        validateFilter(filter);
        int clampedSize = Math.min(Math.max(size, 1), AuditRevisionRepository.MAX_PAGE_SIZE);

        // Fetch one extra row to determine hasMore without a separate COUNT query.
        List<RevisionSummary> rows = repository.search(filter, cursor, clampedSize + 1);

        boolean hasMore = rows.size() > clampedSize;
        List<RevisionSummary> page = hasMore ? rows.subList(0, clampedSize) : rows;

        RevisionCursor nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            RevisionSummary last = page.get(page.size() - 1);
            nextCursor = new RevisionCursor(last.revisionTimestamp().toEpochMilli(),
                    last.revisionNumber());
        }

        return new RevisionPage(page, nextCursor, hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RevisionDetail> getDetail(int revisionNumber, String entityType, UUID entityId) {
        if (entityType == null || !AuditEntityAllowList.isValidEntityType(entityType)) {
            throw new InvalidAuditFilterException("entityType",
                    entityType != null ? entityType : "<null>");
        }
        if (entityId == null) {
            throw new InvalidAuditFilterException("entityId", "<null>");
        }

        String auditTable = AuditEntityAllowList.auditTableFor(entityType);

        Map<String, Object> revInfo = repository.loadRevInfo(revisionNumber);
        if (revInfo.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> currentRow = repository.loadRevisionRow(auditTable, entityId, revisionNumber);
        if (currentRow.isEmpty()) {
            return Optional.empty();
        }

        int predecessorRev = repository.findPredecessorRev(auditTable, entityId, revisionNumber);
        Map<String, Object> previousRow = predecessorRev > 0
                ? repository.loadRevisionRow(auditTable, entityId, predecessorRev)
                : Map.of();

        RevisionDetail detail = RevisionDiffCalculator.compute(
                entityType, entityId, revInfo, currentRow, previousRow);

        log.debug("audit_detail_served revisionNumber={} entityType={} entityId={}",
                revisionNumber, entityType, entityId);

        return Optional.of(detail);
    }

    // ── Validation ────────────────────────────────────────────────────────────────────────

    private void validateFilter(RevisionFilter filter) {
        if (filter.entityType() != null && !AuditEntityAllowList.isValidEntityType(filter.entityType())) {
            throw new InvalidAuditFilterException("entityType", filter.entityType());
        }
        // entityId without entityType is permitted (ignored silently — no table to join).
    }
}

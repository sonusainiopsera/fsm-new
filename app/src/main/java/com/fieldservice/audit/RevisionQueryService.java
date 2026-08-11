package com.fieldservice.audit;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only revision query surface built on the Envers {@link AuditReader}.
 *
 * <p>Access is restricted to privileged roles (DISPATCHER, MANAGER, ADMIN). TECHNICIAN and
 * CUSTOMER principals must not access audit history — audit data can reveal operational details
 * beyond their row-level scope.
 *
 * <p>Queries are paginated; the underlying {@code AuditQuery} uses {@code setFirstResult} and
 * {@code setMaxResults} so the full revision history is never loaded into memory.
 */
@Service
@Transactional(readOnly = true)
public class RevisionQueryService {

    private final EntityManager entityManager;

    public RevisionQueryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Returns paginated revision history for a single work order, ordered newest first.
     *
     * @param workOrderId the work order UUID
     * @param page        zero-based page index
     * @param size        maximum number of entries per page (capped at 100)
     * @return list of revision entries; empty list when the work order has no audit history
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN')")
    public List<WorkOrderRevisionEntry> getWorkOrderRevisions(UUID workOrderId, int page, int size) {
        int cappedSize = Math.min(size, 100);
        AuditReader reader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addOrder(AuditEntity.revisionNumber().desc())
                .setFirstResult(page * cappedSize)
                .setMaxResults(cappedSize)
                .getResultList();

        return results.stream()
                .map(tuple -> {
                    WorkOrder snapshot = (WorkOrder) tuple[0];
                    AuditRevisionEntity rev = (AuditRevisionEntity) tuple[1];
                    RevisionType revType = (RevisionType) tuple[2];
                    return new WorkOrderRevisionEntry(
                            rev.getRev(),
                            rev.getRevisionInstant(),
                            revType.name(),
                            rev.getActorUserId(),
                            rev.getActorRole(),
                            rev.getTraceId(),
                            revType == RevisionType.DEL ? null : toSnapshot(snapshot)
                    );
                })
                .toList();
    }

    /**
     * Returns the total number of revisions recorded for a work order.
     * Uses an aggregate COUNT query to avoid loading all revision numbers into memory.
     */
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN')")
    public long countWorkOrderRevisions(UUID workOrderId) {
        AuditReader reader = AuditReaderFactory.get(entityManager);
        Number count = (Number) reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addProjection(AuditEntity.revisionNumber().count())
                .getSingleResult();
        return count == null ? 0L : count.longValue();
    }

    private WorkOrderSnapshot toSnapshot(WorkOrder wo) {
        if (wo == null) return null;
        return new WorkOrderSnapshot(
                wo.getId(),
                wo.getState() != null ? wo.getState().name() : null,
                wo.getPriority() != null ? wo.getPriority().name() : null,
                wo.getTitle(),
                wo.getDescription(),
                wo.getSlaDeadline(),
                wo.getSiteId(),
                wo.getCustomerId(),
                wo.getAssignedTechnicianId()
        );
    }

    // -------------------------------------------------------------------------
    // Response DTOs
    // -------------------------------------------------------------------------

    public record WorkOrderRevisionEntry(
            int revisionNumber,
            Instant revisionTimestamp,
            String revisionType,
            String actorUserId,
            String actorRole,
            String traceId,
            WorkOrderSnapshot snapshot
    ) {}

    public record WorkOrderSnapshot(
            UUID id,
            String state,
            String priority,
            String title,
            String description,
            Instant slaDeadline,
            UUID siteId,
            UUID customerId,
            UUID assignedTechnicianId
    ) {}
}

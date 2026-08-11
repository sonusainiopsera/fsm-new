package com.fieldservice.workorder.audit;

import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.workorder.domain.WorkOrder;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only revision query service backed by Hibernate Envers {@code AuditReader}.
 *
 * <p>Queries are paginated and ordered descending by revision number so the most recent
 * change is always first. The history is never loaded in full — only the requested page
 * is materialised.
 */
@Service
public class WorkOrderRevisionService {

    private final EntityManager entityManager;

    public WorkOrderRevisionService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PagedRevisionResponse getRevisions(UUID workOrderId, int page, int size) {
        var reader = AuditReaderFactory.get(entityManager);

        long total = ((Number) reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addProjection(AuditEntity.revisionNumber().count())
                .getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addOrder(AuditEntity.revisionNumber().desc())
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        List<RevisionEntry> content = rows.stream()
                .map(row -> RevisionEntry.from(
                        (WorkOrder)   row[0],
                        (AppRevision) row[1],
                        (RevisionType) row[2]))
                .toList();

        return PagedRevisionResponse.of(content, page, size, total);
    }
}

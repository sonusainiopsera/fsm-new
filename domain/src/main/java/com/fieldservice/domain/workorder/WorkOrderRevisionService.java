package com.fieldservice.domain.workorder;

import com.fieldservice.platform.audit.AppRevisionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Read-only service for querying Envers revision history of a work order.
 * Returns paginated revisions ordered newest-first with per-field before/after diffs.
 */
@Service
@Transactional(readOnly = true)
public class WorkOrderRevisionService {

    @PersistenceContext
    private EntityManager em;

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public Page<RevisionDto> getRevisions(UUID workOrderId, Pageable pageable) {
        AuditReader reader = AuditReaderFactory.get(em);

        // All revision numbers ascending — the list is small per entity (<< 10 k)
        List<Number> allRevNums = reader.getRevisions(WorkOrder.class, workOrderId);
        int total = allRevNums.size();

        if (total == 0) {
            return Page.empty(pageable);
        }

        // Build an index from rev long value → ascending position for O(1) prev-rev lookup
        Map<Long, Integer> revToIdx = new HashMap<>(total * 2);
        for (int i = 0; i < total; i++) {
            revToIdx.put(allRevNums.get(i).longValue(), i);
        }

        // Descending order for the response (newest first)
        List<Number> descRevNums = new ArrayList<>(allRevNums);
        Collections.reverse(descRevNums);

        int offset = (int) pageable.getOffset();
        if (offset >= total) {
            return Page.empty(pageable);
        }

        int end = Math.min(offset + pageable.getPageSize(), total);
        List<Number> pageRevNums = descRevNums.subList(offset, end);

        // Load the type map for this page in one query
        @SuppressWarnings("unchecked")
        List<Object[]> typeRows = reader.createQuery()
            .forRevisionsOfEntity(WorkOrder.class, true, true)
            .add(AuditEntity.id().eq(workOrderId))
            .add(AuditEntity.revisionNumber().in(pageRevNums))
            .getResultList();

        Map<Long, RevisionType> typeByRev = new HashMap<>();
        for (Object[] row : typeRows) {
            AppRevisionEntity ri = (AppRevisionEntity) row[1];
            typeByRev.put(ri.getRev(), (RevisionType) row[2]);
        }

        List<RevisionDto> dtos = pageRevNums.stream().map(revNum -> {
            long revLong = revNum.longValue();
            AppRevisionEntity revInfo = reader.findRevision(AppRevisionEntity.class, revNum);
            WorkOrder current = reader.find(WorkOrder.class, workOrderId, revNum);

            RevisionType revType = typeByRev.getOrDefault(revLong, RevisionType.MOD);

            // Load previous state for diff
            int idx = revToIdx.getOrDefault(revLong, -1);
            WorkOrder previous = (idx > 0)
                ? reader.find(WorkOrder.class, workOrderId, allRevNums.get(idx - 1))
                : null;

            return new RevisionDto(
                revInfo.getRev(),
                Instant.ofEpochMilli(revInfo.getRevtstmp()),
                revInfo.getActorUserId(),
                revInfo.getActorRole(),
                revInfo.getTraceId(),
                revInfo.getClientIp(),
                switch (revType) {
                    case ADD -> "CREATE";
                    case MOD -> "UPDATE";
                    case DEL -> "DELETE";
                },
                computeChanges(previous, current)
            );
        }).toList();

        return new PageImpl<>(dtos, pageable, total);
    }

    private List<FieldChangeDto> computeChanges(WorkOrder before, WorkOrder after) {
        if (before == null && after == null) return List.of();
        List<FieldChangeDto> changes = new ArrayList<>();
        compareField(changes, "title",
            before == null ? null : before.getTitle(),
            after  == null ? null : after.getTitle());
        compareField(changes, "state",
            before == null ? null : (before.getState() == null ? null : before.getState().name()),
            after  == null ? null : (after.getState()  == null ? null : after.getState().name()));
        compareField(changes, "assignedTechnicianId",
            before == null ? null : before.getAssignedTechnicianId(),
            after  == null ? null : after.getAssignedTechnicianId());
        compareField(changes, "priority",
            before == null ? null : before.getPriority(),
            after  == null ? null : after.getPriority());
        return changes;
    }

    private void compareField(List<FieldChangeDto> out, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            out.add(new FieldChangeDto(name, before, after));
        }
    }
}

package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.workorder.api.dto.RevisionEntryDto;
import com.fieldservice.workorder.api.dto.TimelineEventDto;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only service exposing Envers revision history and a derived human-readable timeline
 * for work orders, with role-aware access scoping and DTO assembly.
 *
 * <p><strong>Security model:</strong>
 * <ul>
 *   <li>Privileged (DISPATCHER / MANAGER / ADMIN) — full history for all in-scope work orders.</li>
 *   <li>TECHNICIAN — history only for work orders where they are or were the assignee;
 *       Confidential fields are omitted.</li>
 *   <li>CUSTOMER — redacted timeline only; internal notes (description, faultDescription)
 *       and technician identity are omitted from every response.</li>
 * </ul>
 *
 * <p>Masking is applied at assembly time in {@link #buildAllowedFields} and
 * {@link #resolveDisplayName}, not by post-filtering a fully populated payload.
 *
 * <p><strong>Immutability guarantee:</strong> this class is {@code @Transactional(readOnly = true)}
 * and only queries the {@code AuditReader}; no repository write method is reachable through
 * this service.
 */
@Service
@Transactional(readOnly = true)
public class WorkOrderHistoryService {

    static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_FIELD_VALUE_LENGTH = 500;
    private static final String TRUNCATION_SUFFIX = "…"; // ellipsis character

    // Fields visible to all roles that have access
    private static final Set<String> BASE_FIELDS = Set.of(
            "state", "priority", "title", "reference", "slaDeadline");

    // Additional fields visible to privileged and technician (omitted for customer)
    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "description", "assignedTechnicianId", "siteId");

    // Fields visible only to privileged roles (CONFIDENTIAL classification)
    private static final Set<String> CONFIDENTIAL_FIELDS = Set.of(
            "faultDescription", "customerId");

    private final EntityManager entityManager;
    private final WorkOrderRepository workOrderRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final AccessScopeResolver scopeResolver;

    public WorkOrderHistoryService(
            EntityManager entityManager,
            WorkOrderRepository workOrderRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            AccessScopeResolver scopeResolver) {
        this.entityManager = entityManager;
        this.workOrderRepository = workOrderRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Returns a paginated list of Envers revisions for the given work order, newest first.
     *
     * @param workOrderId   the work order UUID
     * @param page          zero-based page index
     * @param requestedSize entries per page (clamped to {@value #MAX_PAGE_SIZE})
     * @return paginated revision entries; empty if no history exists
     * @throws ScopedAccessDeniedException if the work order is out of scope or does not exist
     */
    public PagedResponse<RevisionEntryDto> getRevisions(UUID workOrderId, int page, int requestedSize) {
        int pageSize = Math.min(requestedSize, MAX_PAGE_SIZE);
        AccessScope scope = scopeResolver.resolve();
        checkAccess(scope, workOrderId);

        AuditReader reader = AuditReaderFactory.get(entityManager);
        long total = countRevisions(reader, workOrderId);
        if (total == 0) {
            return PagedResponse.empty(page, pageSize);
        }

        int offset = page * pageSize;
        List<Object[]> fetched = queryRevisions(reader, workOrderId, offset, pageSize + 1);
        List<Object[]> pageRows = fetched.size() > pageSize ? fetched.subList(0, pageSize) : fetched;
        WorkOrder prevSnapshot = fetched.size() > pageSize ? (WorkOrder) fetched.get(pageSize)[0] : null;

        Set<String> allowedFields = buildAllowedFields(scope);
        List<RevisionEntryDto> entries = new ArrayList<>(pageRows.size());

        for (int i = 0; i < pageRows.size(); i++) {
            Object[] tuple = pageRows.get(i);
            WorkOrder snapshot = (WorkOrder) tuple[0];
            AuditRevisionEntity rev = (AuditRevisionEntity) tuple[1];
            RevisionType revType = (RevisionType) tuple[2];

            WorkOrder olderSnapshot = (i + 1 < pageRows.size())
                    ? (WorkOrder) pageRows.get(i + 1)[0]
                    : prevSnapshot;

            List<RevisionEntryDto.FieldChangeDto> changes =
                    computeDiff(olderSnapshot, snapshot, allowedFields, revType);

            entries.add(new RevisionEntryDto(
                    rev.getRev(),
                    rev.getRevisionInstant(),
                    resolveDisplayName(rev.getActorUserId(), rev.getActorRole(), scope.isCustomer()),
                    revType.name(),
                    changes));
        }

        return PagedResponse.of(entries, PageMeta.of(page, pageSize, total), PageLinks.none());
    }

    /**
     * Returns a paginated derived timeline for the given work order, newest first.
     * Only revisions that represent a meaningful lifecycle event are included.
     *
     * @param workOrderId   the work order UUID
     * @param page          zero-based page index
     * @param requestedSize events per page (clamped to {@value #MAX_PAGE_SIZE})
     * @return paginated timeline events; empty if no history exists
     * @throws ScopedAccessDeniedException if the work order is out of scope or does not exist
     */
    public PagedResponse<TimelineEventDto> getTimeline(UUID workOrderId, int page, int requestedSize) {
        int pageSize = Math.min(requestedSize, MAX_PAGE_SIZE);
        AccessScope scope = scopeResolver.resolve();
        checkAccess(scope, workOrderId);
        boolean isCustomer = scope.isCustomer();

        AuditReader reader = AuditReaderFactory.get(entityManager);
        long total = countRevisions(reader, workOrderId);
        if (total == 0) {
            return PagedResponse.empty(page, pageSize);
        }

        int offset = page * pageSize;
        List<Object[]> fetched = queryRevisions(reader, workOrderId, offset, pageSize + 1);
        List<Object[]> pageRows = fetched.size() > pageSize ? fetched.subList(0, pageSize) : fetched;
        WorkOrder prevSnapshot = fetched.size() > pageSize ? (WorkOrder) fetched.get(pageSize)[0] : null;

        List<TimelineEventDto> events = new ArrayList<>(pageRows.size());

        for (int i = 0; i < pageRows.size(); i++) {
            Object[] tuple = pageRows.get(i);
            WorkOrder snapshot = (WorkOrder) tuple[0];
            AuditRevisionEntity rev = (AuditRevisionEntity) tuple[1];
            RevisionType revType = (RevisionType) tuple[2];

            WorkOrder olderSnapshot = (i + 1 < pageRows.size())
                    ? (WorkOrder) pageRows.get(i + 1)[0]
                    : prevSnapshot;

            String eventType = deriveEventType(revType, olderSnapshot, snapshot);
            if (eventType == null) {
                continue; // non-significant revision — omit from timeline
            }

            events.add(new TimelineEventDto(
                    eventType,
                    rev.getRevisionInstant(),
                    resolveDisplayName(rev.getActorUserId(), rev.getActorRole(), isCustomer),
                    buildDetail(olderSnapshot, snapshot, isCustomer)));
        }

        return PagedResponse.of(events, PageMeta.of(page, pageSize, total), PageLinks.none());
    }

    // -------------------------------------------------------------------------
    // Access control
    // -------------------------------------------------------------------------

    private void checkAccess(AccessScope scope, UUID workOrderId) {
        if (scope.isPrivileged() || scope.isCustomer()) {
            // ScopedQueryExecutor uses the scope predicate; throws 403 for out-of-scope/not-found
            scopedQueryExecutor.findById(WorkOrder.class, workOrderId, workOrderRepository);
            return;
        }

        if (scope.isTechnician()) {
            if (scope.technicianId() == null) {
                throw new ScopedAccessDeniedException(
                        "TECHNICIAN principal is missing technicianId in access scope");
            }
            // Allow access if the technician is or WAS the assignee (check Envers history)
            AuditReader reader = AuditReaderFactory.get(entityManager);
            @SuppressWarnings("unchecked")
            List<Object> matched = reader.createQuery()
                    .forRevisionsOfEntity(WorkOrder.class, true, false)
                    .add(AuditEntity.id().eq(workOrderId))
                    .add(AuditEntity.property("assignedTechnicianId").eq(scope.technicianId()))
                    .setMaxResults(1)
                    .getResultList();
            if (matched.isEmpty()) {
                // Non-disclosure: same exception for not-found and out-of-scope
                throw new ScopedAccessDeniedException("WorkOrder", workOrderId);
            }
            return;
        }

        throw new ScopedAccessDeniedException(
                "No access policy for roles: " + scope.roles());
    }

    // -------------------------------------------------------------------------
    // Envers queries
    // -------------------------------------------------------------------------

    private long countRevisions(AuditReader reader, UUID workOrderId) {
        Number count = (Number) reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addProjection(AuditEntity.revisionNumber().count())
                .getSingleResult();
        return count == null ? 0L : count.longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryRevisions(AuditReader reader, UUID workOrderId, int offset, int limit) {
        return reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addOrder(AuditEntity.revisionNumber().desc())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    // -------------------------------------------------------------------------
    // Field diff computation
    // -------------------------------------------------------------------------

    Set<String> buildAllowedFields(AccessScope scope) {
        if (scope.isPrivileged()) {
            var fields = new java.util.LinkedHashSet<>(BASE_FIELDS);
            fields.addAll(INTERNAL_FIELDS);
            fields.addAll(CONFIDENTIAL_FIELDS);
            return Set.copyOf(fields);
        }
        if (scope.isTechnician()) {
            var fields = new java.util.LinkedHashSet<>(BASE_FIELDS);
            fields.addAll(INTERNAL_FIELDS);
            return Set.copyOf(fields);
        }
        // CUSTOMER — base fields only; no internal notes, no technician identity
        return BASE_FIELDS;
    }

    List<RevisionEntryDto.FieldChangeDto> computeDiff(
            WorkOrder before, WorkOrder after, Set<String> allowedFields, RevisionType revType) {

        if (revType == RevisionType.ADD || before == null) {
            // New entity — show all allowed fields with null before
            List<RevisionEntryDto.FieldChangeDto> changes = new ArrayList<>();
            if (after == null) return changes;
            for (String field : allowedFields) {
                String value = extractField(after, field);
                if (value != null) {
                    changes.add(new RevisionEntryDto.FieldChangeDto(field, null, value));
                }
            }
            return changes;
        }

        if (revType == RevisionType.DEL || after == null) {
            return List.of(); // snapshot not available for DEL revisions
        }

        List<RevisionEntryDto.FieldChangeDto> changes = new ArrayList<>();
        for (String field : allowedFields) {
            String beforeVal = extractField(before, field);
            String afterVal = extractField(after, field);
            if (!Objects.equals(beforeVal, afterVal)) {
                changes.add(new RevisionEntryDto.FieldChangeDto(field, beforeVal, afterVal));
            }
        }
        return changes;
    }

    private String extractField(WorkOrder wo, String field) {
        if (wo == null) return null;
        String raw = switch (field) {
            case "state"               -> wo.getState() != null ? wo.getState().name() : null;
            case "priority"            -> wo.getPriority() != null ? wo.getPriority().name() : null;
            case "title"               -> wo.getTitle();
            case "reference"           -> wo.getReference();
            case "slaDeadline"         -> wo.getSlaDeadline() != null ? wo.getSlaDeadline().toString() : null;
            case "description"         -> wo.getDescription();
            case "assignedTechnicianId"-> wo.getAssignedTechnicianId() != null
                                            ? wo.getAssignedTechnicianId().toString() : null;
            case "siteId"              -> wo.getSiteId() != null ? wo.getSiteId().toString() : null;
            case "customerId"          -> wo.getCustomerId() != null ? wo.getCustomerId().toString() : null;
            case "faultDescription"    -> wo.getFaultDescription();
            default                    -> null;
        };
        return truncate(raw);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_FIELD_VALUE_LENGTH) return value;
        return value.substring(0, MAX_FIELD_VALUE_LENGTH) + TRUNCATION_SUFFIX;
    }

    // -------------------------------------------------------------------------
    // Timeline derivation
    // -------------------------------------------------------------------------

    String deriveEventType(RevisionType revType, WorkOrder before, WorkOrder after) {
        if (revType == RevisionType.ADD) {
            return "CREATED";
        }
        if (revType == RevisionType.DEL) {
            return "CANCELLED";
        }
        if (after == null) {
            return null;
        }

        WorkOrderState prevState = before != null ? before.getState() : null;
        WorkOrderState newState = after.getState();

        if (!Objects.equals(prevState, newState) && newState != null) {
            return switch (newState) {
                case ASSIGNED    -> "ASSIGNED";
                case EN_ROUTE    -> "DEPARTED";
                case IN_PROGRESS -> (prevState == WorkOrderState.ON_HOLD) ? "RESUMED" : "STARTED";
                case ON_HOLD     -> "HELD";
                case COMPLETED   -> "COMPLETED";
                case CLOSED      -> "CLOSED";
                case CANCELLED   -> "CANCELLED";
                default          -> null;
            };
        }

        // State unchanged — check for technician reassignment
        UUID prevTech = before != null ? before.getAssignedTechnicianId() : null;
        UUID newTech = after.getAssignedTechnicianId();
        if (newTech != null && !Objects.equals(prevTech, newTech) && prevTech != null) {
            return "REASSIGNED";
        }

        return null; // no significant lifecycle change in this revision
    }

    private Map<String, String> buildDetail(WorkOrder before, WorkOrder after, boolean isCustomer) {
        Map<String, String> detail = new LinkedHashMap<>();
        if (after == null) return detail;

        WorkOrderState prevState = before != null ? before.getState() : null;
        WorkOrderState newState = after.getState();

        if (prevState != null) detail.put("fromState", prevState.name());
        if (newState != null) detail.put("toState", newState.name());

        if (!isCustomer) {
            // Include technician identity for internal roles
            UUID techId = after.getAssignedTechnicianId();
            if (techId != null) {
                detail.put("technicianId", techId.toString());
            }
        }
        return detail;
    }

    // -------------------------------------------------------------------------
    // Actor attribution
    // -------------------------------------------------------------------------

    String resolveDisplayName(String actorUserId, String actorRole, boolean isCustomer) {
        if (actorUserId == null || "system".equalsIgnoreCase(actorUserId)) {
            return "System";
        }
        String role = actorRole != null ? actorRole : "UNKNOWN";
        if (isCustomer) {
            // Never expose internal user identifiers to customers
            return role;
        }
        // Safe prefix of userId for log correlation without full exposure
        String idPreview = actorUserId.length() > 8
                ? actorUserId.substring(0, 8) + "..."
                : actorUserId;
        return role + " [" + idPreview + "]";
    }
}

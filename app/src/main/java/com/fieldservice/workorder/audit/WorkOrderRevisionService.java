package com.fieldservice.workorder.audit;

import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.holds.WorkOrderHold;
import com.fieldservice.workorder.holds.WorkOrderHoldRepository;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only revision and timeline query service backed by Hibernate Envers {@code AuditReader}.
 *
 * <p>All methods perform an AccessScope pre-check on the parent work order using the same
 * row-scope predicate as the main read paths — if the work order is not within the caller's
 * scope the methods return 403 (via {@link ScopedAccessDeniedException}) without disclosing
 * whether the work order exists.
 *
 * <p>Field diffs are computed by comparing consecutive revision snapshots for an allow-listed
 * set of fields. Restricted-class fields are never included; Confidential fields are omitted
 * for CUSTOMER-role callers before any record is populated.
 *
 * <p>This service is strictly read-only: no mutating query or repository method is called.
 */
@Service
public class WorkOrderRevisionService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderRevisionService.class);

    /** Maximum description length in a diff value to prevent multi-megabyte payloads. */
    private static final int DESCRIPTION_TRUNCATE_LIMIT = 200;

    /**
     * Allow-listed fields that may appear in revision diffs.
     * Any field not in this list is silently excluded regardless of what Envers captured.
     */
    private static final List<String> ALLOWED_DIFF_FIELDS_ALL = List.of(
            "state", "priority", "assignedTechnicianId", "faultCode", "faultCategory", "description"
    );

    /** Allow-listed fields visible to CUSTOMER-role callers — excludes Confidential fields. */
    private static final List<String> ALLOWED_DIFF_FIELDS_CUSTOMER = List.of(
            "state", "priority", "faultCode", "faultCategory"
    );

    private final EntityManager entityManager;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final AppUserRepository appUserRepository;
    private final WorkOrderHoldRepository holdRepository;

    public WorkOrderRevisionService(
            EntityManager entityManager,
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            AppUserRepository appUserRepository,
            WorkOrderHoldRepository holdRepository) {
        this.entityManager        = entityManager;
        this.scopedQueryExecutor  = scopedQueryExecutor;
        this.workOrderRepository  = workOrderRepository;
        this.appUserRepository    = appUserRepository;
        this.holdRepository       = holdRepository;
    }

    // -------------------------------------------------------------------------
    // Revisions endpoint
    // -------------------------------------------------------------------------

    /**
     * Returns a page of revision entries for the given work order, ordered newest first.
     *
     * <p>An AccessScope pre-check gates the query: TECHNICIAN callers see only work orders
     * they are currently assigned to; CUSTOMER callers see only work orders for their sites.
     * Out-of-scope (or non-existent) requests throw {@link ScopedAccessDeniedException}.
     *
     * @param workOrderId the work order to query
     * @param scope       the resolved access scope of the caller
     * @param page        zero-based page number
     * @param size        page size (1–50)
     * @return paginated revision history
     */
    @Transactional(readOnly = true)
    public PagedResponse<RevisionEntry> getRevisions(UUID workOrderId, AccessScope scope, int page, int size) {
        checkAccess(workOrderId, scope);

        var reader = AuditReaderFactory.get(entityManager);
        boolean isCustomer = scope.isCustomer();
        List<String> allowedFields = isCustomer ? ALLOWED_DIFF_FIELDS_CUSTOMER : ALLOWED_DIFF_FIELDS_ALL;

        // Count total revisions for pagination metadata.
        long total = ((Number) reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addProjection(AuditEntity.revisionNumber().count())
                .getSingleResult()).longValue();

        if (total == 0) {
            return PagedResponse.empty(size);
        }

        // Load page+1 rows so the last entry in the page has a "before" context.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addOrder(AuditEntity.revisionNumber().desc())
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();

        // Resolve display names for all distinct actors in one pass.
        Map<String, String> displayNameCache = buildDisplayNameCache(rows, isCustomer);

        List<RevisionEntry> content = new ArrayList<>(Math.min(rows.size(), size));
        for (int i = 0; i < Math.min(rows.size(), size); i++) {
            Object[] current  = rows.get(i);
            Object[] previous = (i + 1 < rows.size()) ? rows.get(i + 1) : null;

            WorkOrder  currentWo  = (WorkOrder)  current[0];
            AppRevision currentRev = (AppRevision) current[1];
            RevisionType type      = (RevisionType) current[2];

            WorkOrder previousWo = (previous != null) ? (WorkOrder) previous[0] : null;

            List<FieldChangeDto> changes = computeDiffs(currentWo, previousWo, type, allowedFields);
            String displayName = displayNameCache.get(currentRev.getActorUserId());

            content.add(new RevisionEntry(
                    currentRev.getId(),
                    currentRev.getRevisionInstant(),
                    displayName,
                    type.name(),
                    changes
            ));
        }

        PageMeta meta = PageMeta.of(page, size, total);
        return PagedResponse.of(content, meta, PageLinks.none());
    }

    // -------------------------------------------------------------------------
    // Timeline endpoint
    // -------------------------------------------------------------------------

    /**
     * Returns a page of derived, human-readable timeline events for the given work order.
     *
     * <p>Timeline events use the stable vocabulary: CREATED, ASSIGNED, REASSIGNED, DEPARTED,
     * STARTED, HELD, RESUMED, COMPLETED, CLOSED, CANCELLED.
     *
     * <p>For CUSTOMER-role callers: technician identity is replaced with "Service Team" and
     * internal notes (description changes) are excluded from the detail map.
     *
     * @param workOrderId the work order to query
     * @param scope       the resolved access scope of the caller
     * @param page        zero-based page number
     * @param size        page size (1–50)
     * @return paginated timeline events in reverse chronological order
     */
    @Transactional(readOnly = true)
    public PagedResponse<TimelineEventDto> getTimeline(UUID workOrderId, AccessScope scope, int page, int size) {
        checkAccess(workOrderId, scope);

        var reader = AuditReaderFactory.get(entityManager);
        boolean isCustomer = scope.isCustomer();

        // Load ALL revisions ordered ascending to derive sequential events.
        @SuppressWarnings("unchecked")
        List<Object[]> allRows = reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        if (allRows.isEmpty()) {
            return PagedResponse.empty(size);
        }

        // Load holds for this work order (for HELD event detail).
        List<WorkOrderHold> holds = holdRepository.findByWorkOrderId(workOrderId);

        Map<String, String> displayNameCache = buildDisplayNameCache(allRows, isCustomer);
        List<TimelineEventDto> allEvents = new ArrayList<>();

        for (int i = 0; i < allRows.size(); i++) {
            Object[] current  = allRows.get(i);
            Object[] previous = (i > 0) ? allRows.get(i - 1) : null;

            WorkOrder   currentWo  = (WorkOrder)   current[0];
            AppRevision currentRev = (AppRevision)  current[1];
            RevisionType type      = (RevisionType) current[2];
            WorkOrder   previousWo = (previous != null) ? (WorkOrder) previous[0] : null;

            String displayName = displayNameCache.get(currentRev.getActorUserId());
            List<TimelineEventDto> events = deriveEvents(
                    currentWo, previousWo, type, currentRev, displayName, holds, isCustomer);
            allEvents.addAll(events);
        }

        // Reverse to get newest-first ordering for the page response.
        allEvents.sort(Comparator.comparing(TimelineEventDto::occurredAt).reversed());

        long total = allEvents.size();
        int fromIdx = page * size;
        if (fromIdx >= total) {
            return PagedResponse.empty(size);
        }
        int toIdx = (int) Math.min(fromIdx + size, total);
        List<TimelineEventDto> pageContent = allEvents.subList(fromIdx, toIdx);

        PageMeta meta = PageMeta.of(page, size, total);
        return PagedResponse.of(pageContent, meta, PageLinks.none());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Throws {@link ScopedAccessDeniedException} if the caller has no scope on the work order. */
    private void checkAccess(UUID workOrderId, AccessScope scope) {
        // ADMIN/MANAGER have full access — skip the scoped load for performance.
        if (scope.isPrivileged()) {
            return;
        }
        Optional<WorkOrder> wo = scopedQueryExecutor.findById(
                workOrderRepository, workOrderId, scope, WorkOrder.class);
        if (wo.isEmpty()) {
            log.warn("history_access_denied workOrderId={} userId={}", workOrderId, scope.userId());
            throw new ScopedAccessDeniedException(
                    "Work order not found or not accessible to caller");
        }
    }

    /** Computes allow-listed field diffs between two consecutive revision snapshots. */
    private List<FieldChangeDto> computeDiffs(
            WorkOrder current, WorkOrder previous, RevisionType type, List<String> allowedFields) {

        List<FieldChangeDto> diffs = new ArrayList<>();

        for (String field : allowedFields) {
            String currentVal  = extractField(current, field);
            String previousVal = (previous != null) ? extractField(previous, field) : null;

            // For ADD revisions, "before" is always null; for DEL revisions, "after" is null.
            if (type == RevisionType.ADD) {
                if (currentVal != null) {
                    diffs.add(new FieldChangeDto(field, null, currentVal));
                }
            } else if (type == RevisionType.DEL) {
                if (previousVal != null) {
                    diffs.add(new FieldChangeDto(field, previousVal, null));
                }
            } else {
                // MOD — only add if value actually changed.
                if (!Objects.equals(currentVal, previousVal)) {
                    diffs.add(new FieldChangeDto(field, previousVal, currentVal));
                }
            }
        }
        return diffs;
    }

    /** Extracts a named allow-listed field from a WorkOrder snapshot as a String. */
    private String extractField(WorkOrder wo, String field) {
        if (wo == null) return null;
        return switch (field) {
            case "state"                -> wo.getState() != null ? wo.getState().name() : null;
            case "priority"             -> wo.getPriority();
            case "assignedTechnicianId" -> wo.getAssignedTechnicianId() != null
                                           ? wo.getAssignedTechnicianId().toString() : null;
            case "faultCode"            -> wo.getFaultCode();
            case "faultCategory"        -> wo.getFaultCategory();
            case "description"          -> truncate(wo.getDescription());
            default                     -> null;
        };
    }

    /** Truncates a string to {@link #DESCRIPTION_TRUNCATE_LIMIT} with an ellipsis marker. */
    private String truncate(String value) {
        if (value == null) return null;
        if (value.length() <= DESCRIPTION_TRUNCATE_LIMIT) return value;
        return value.substring(0, DESCRIPTION_TRUNCATE_LIMIT) + "…";
    }

    /**
     * Derives zero or more timeline events from a single revision transition.
     * Events are derived by comparing the current state snapshot to the previous.
     */
    private List<TimelineEventDto> deriveEvents(
            WorkOrder current, WorkOrder previous, RevisionType type,
            AppRevision rev, String displayName,
            List<WorkOrderHold> holds, boolean isCustomer) {

        List<TimelineEventDto> events = new ArrayList<>();

        // ADD revision → CREATED event
        if (type == RevisionType.ADD || previous == null) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("toState", current.getState() != null ? current.getState().name() : null);
            events.add(new TimelineEventDto("CREATED", rev.getRevisionInstant(), displayName, detail));
            return events;
        }

        String prevState = previous.getState() != null ? previous.getState().name() : null;
        String currState = current.getState()  != null ? current.getState().name()  : null;
        boolean stateChanged = !Objects.equals(prevState, currState);

        UUID prevTech = previous.getAssignedTechnicianId();
        UUID currTech = current.getAssignedTechnicianId();
        boolean techChanged = !Objects.equals(prevTech, currTech);

        if (stateChanged && currState != null) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("fromState", prevState);
            detail.put("toState", currState);

            String eventType = switch (currState) {
                case "ASSIGNED" -> {
                    // Distinguish ASSIGNED (first tech) from REASSIGNED (tech swap)
                    if (techChanged && prevTech != null && !isCustomer) {
                        detail.put("previousTechnicianId", prevTech.toString());
                        yield "REASSIGNED";
                    }
                    yield "ASSIGNED";
                }
                case "EN_ROUTE"     -> "DEPARTED";
                case "IN_PROGRESS"  -> "ON_HOLD".equals(prevState) ? "RESUMED" : "STARTED";
                case "ON_HOLD"      -> {
                    // Attach hold reason code from closest hold record.
                    findHoldReason(holds, rev.getRevisionInstant())
                            .ifPresent(r -> detail.put("holdReasonCode", r));
                    yield "HELD";
                }
                case "COMPLETED"    -> "COMPLETED";
                case "CLOSED"       -> "CLOSED";
                case "CANCELLED"    -> "CANCELLED";
                default             -> null;
            };

            if (eventType != null) {
                events.add(new TimelineEventDto(eventType, rev.getRevisionInstant(), displayName, detail));
            }
        } else if (!stateChanged && techChanged && currTech != null && !isCustomer) {
            // Tech change without state change → REASSIGNED
            Map<String, Object> detail = new HashMap<>();
            if (prevTech != null) detail.put("previousTechnicianId", prevTech.toString());
            detail.put("toState", currState);
            events.add(new TimelineEventDto("REASSIGNED", rev.getRevisionInstant(), displayName, detail));
        }

        return events;
    }

    /**
     * Finds the hold reason code for the hold record nearest to the given revision timestamp.
     * Returns empty if no hold record can be matched (defensive).
     */
    private Optional<String> findHoldReason(List<WorkOrderHold> holds, java.time.Instant revisionAt) {
        if (holds == null || holds.isEmpty()) return Optional.empty();
        // Find the hold whose startedAt is closest to (and not after) the revision timestamp.
        return holds.stream()
                .filter(h -> !h.getStartedAt().isAfter(revisionAt.plusSeconds(5)))
                .max(Comparator.comparing(WorkOrderHold::getStartedAt))
                .map(WorkOrderHold::getReasonCode);
    }

    /**
     * Builds a map of actorUserId → displayName for all actors in the given revision rows.
     * Customer-facing calls always get "Service Team" to avoid Confidential identity disclosure.
     */
    private Map<String, String> buildDisplayNameCache(List<Object[]> rows, boolean isCustomer) {
        Map<String, String> cache = new HashMap<>();
        for (Object[] row : rows) {
            AppRevision rev = (AppRevision) row[1];
            String actorId = rev.getActorUserId();
            if (!cache.containsKey(actorId)) {
                cache.put(actorId, isCustomer ? "Service Team" : resolveDisplayName(actorId));
            }
        }
        return cache;
    }

    /** Resolves a display name from an actor user ID without exposing the internal identifier. */
    private String resolveDisplayName(String actorUserId) {
        if (actorUserId == null || "SYSTEM".equals(actorUserId)) {
            return "System";
        }
        try {
            UUID userId = UUID.fromString(actorUserId);
            return appUserRepository.findById(userId)
                    .map(u -> {
                        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
                            return u.getDisplayName();
                        }
                        // Fallback: first segment of email without domain
                        String email = u.getEmail();
                        return (email != null && email.contains("@"))
                                ? email.substring(0, email.indexOf('@'))
                                : "User";
                    })
                    .orElse("Former User");
        } catch (IllegalArgumentException e) {
            // actorUserId is not a UUID — use role-based fallback
            return "System";
        }
    }
}

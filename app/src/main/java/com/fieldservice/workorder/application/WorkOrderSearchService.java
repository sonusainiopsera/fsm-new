package com.fieldservice.workorder.application;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.pagination.SpecificationPageService;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.workorder.api.dto.WorkOrderBoardRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Implements scoped, paginated, filterable work order search (WO-127).
 *
 * <p>Row scope is enforced by {@link SpecificationPageService} via the
 * {@link com.fieldservice.platform.persistence.ScopedQueryExecutor} — the scope predicate
 * is always ANDed into the repository query before execution, so out-of-scope rows
 * are never loaded and cannot leak through counts or log lines.
 *
 * <p>{@link SortAllowList} rejects unknown sort fields with 400 before any query is built.
 */
@Service
@Transactional(readOnly = true)
public class WorkOrderSearchService {

    /** Terminal states excluded from the at-risk predicate. */
    static final Set<WorkOrderState> TERMINAL_STATES = Set.of(
            WorkOrderState.COMPLETED, WorkOrderState.CLOSED, WorkOrderState.CANCELLED);

    static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            "createdAt",          "createdAt",
            "updatedAt",          "updatedAt",
            "priority",           "priority",
            "state",              "state",
            "resolutionDeadline", "slaDeadline"
    );

    private final SpecificationPageService specificationPageService;
    private final WorkOrderRepository workOrderRepository;
    private final MeterRegistry meterRegistry;

    public WorkOrderSearchService(
            SpecificationPageService specificationPageService,
            WorkOrderRepository workOrderRepository,
            MeterRegistry meterRegistry) {
        this.specificationPageService = specificationPageService;
        this.workOrderRepository = workOrderRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Executes a scoped, paginated work order search.
     *
     * @param criteria filter criteria (all fields optional)
     * @param pageQuery pagination and sort parameters
     * @param request  current HTTP request (for link generation)
     * @return page of board-projection DTOs scoped to the authenticated principal
     */
    @PreAuthorize("isAuthenticated()")
    public PagedResponse<WorkOrderBoardRow> search(
            WorkOrderSearchCriteria criteria,
            PageQuery pageQuery,
            HttpServletRequest request) {

        String personaTag = resolvePersonaTag();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Specification<WorkOrder> filterSpec = buildFilterSpec(criteria);
            PagedResponse<WorkOrder> raw = specificationPageService.findPage(
                    WorkOrder.class, filterSpec, pageQuery, SORT_ALLOW_LIST,
                    workOrderRepository, "work_order", request);

            List<WorkOrderBoardRow> rows = raw.data().stream()
                    .map(WorkOrderSearchService::toRow)
                    .toList();
            return PagedResponse.of(rows, raw.page(), raw.links());
        } finally {
            sample.stop(meterRegistry.timer("wo_search_duration",
                    "persona", personaTag,
                    "hasFilter", String.valueOf(criteria != null && hasAnyFilter(criteria))));
        }
    }

    // ── Specification builder ────────────────────────────────────────────────

    @Nullable
    static Specification<WorkOrder> buildFilterSpec(@Nullable WorkOrderSearchCriteria criteria) {
        if (criteria == null) {
            return null;
        }
        List<Specification<WorkOrder>> parts = new ArrayList<>();

        if (criteria.states() != null && !criteria.states().isEmpty()) {
            List<WorkOrderState> states = criteria.states();
            parts.add((root, q, cb) -> root.get("state").in(states));
        }
        if (criteria.priority() != null) {
            WorkOrderPriority priority = criteria.priority();
            parts.add((root, q, cb) -> cb.equal(root.get("priority"), priority));
        }
        if (criteria.assignedTechnicianId() != null) {
            parts.add((root, q, cb) ->
                    cb.equal(root.get("assignedTechnicianId"), criteria.assignedTechnicianId()));
        }
        if (criteria.customerId() != null) {
            parts.add((root, q, cb) ->
                    cb.equal(root.get("customerId"), criteria.customerId()));
        }
        if (criteria.siteId() != null) {
            parts.add((root, q, cb) ->
                    cb.equal(root.get("siteId"), criteria.siteId()));
        }
        if (criteria.createdFrom() != null) {
            Instant from = criteria.createdFrom();
            parts.add((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (criteria.createdTo() != null) {
            Instant to = criteria.createdTo();
            parts.add((root, q, cb) ->
                    cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        if (criteria.deadlineFrom() != null) {
            Instant from = criteria.deadlineFrom();
            parts.add((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("slaDeadline"), from));
        }
        if (criteria.deadlineTo() != null) {
            Instant to = criteria.deadlineTo();
            parts.add((root, q, cb) ->
                    cb.lessThanOrEqualTo(root.get("slaDeadline"), to));
        }
        if (Boolean.TRUE.equals(criteria.atRisk())) {
            Instant now = Instant.now();
            parts.add((root, q, cb) -> cb.and(
                    cb.isNotNull(root.get("slaDeadline")),
                    cb.lessThan(root.get("slaDeadline"), now),
                    root.get("state").in(TERMINAL_STATES).not()
            ));
        }

        if (parts.isEmpty()) {
            return null;
        }
        return parts.stream().reduce(Specification::and).orElse(null);
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    static WorkOrderBoardRow toRow(WorkOrder wo) {
        boolean atRisk = wo.getSlaDeadline() != null
                && wo.getSlaDeadline().isBefore(Instant.now())
                && !TERMINAL_STATES.contains(wo.getState());
        return new WorkOrderBoardRow(
                wo.getId(),
                wo.getTitle(),
                wo.getState(),
                wo.getPriority(),
                wo.getCustomerId(),
                wo.getSiteId(),
                wo.getAssignedTechnicianId(),
                wo.getSlaDeadline(),
                atRisk,
                wo.getCumulativeHoldMinutes(),
                wo.getVersion(),
                wo.getCreatedAt(),
                wo.getUpdatedAt());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean hasAnyFilter(WorkOrderSearchCriteria c) {
        return c.states() != null || c.priority() != null || c.assignedTechnicianId() != null
                || c.customerId() != null || c.siteId() != null
                || c.createdFrom() != null || c.createdTo() != null
                || c.deadlineFrom() != null || c.deadlineTo() != null
                || c.atRisk() != null;
    }

    private String resolvePersonaTag() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication();
            if (auth == null) return "anonymous";
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replace("ROLE_", "").toLowerCase())
                    .findFirst().orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}

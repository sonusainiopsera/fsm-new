package com.fieldservice.workorder.application;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.pagination.SortField;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.workorder.application.dto.TechnicianJobSummary;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serves the technician day-list: scoped, windowed, carry-over-aware (WO-154).
 *
 * <p>Row scope is enforced as a SQL predicate by {@link ScopedQueryExecutor} — out-of-scope
 * rows are never loaded. The day-window AND carry-over predicates are composed with the
 * mandatory AccessScope predicate, so the query returns only rows the caller may see.
 *
 * <p>Carry-over rule: a job in ASSIGNED, EN_ROUTE, IN_PROGRESS or ON_HOLD with
 * {@code scheduled_window_start} before today (or null) is always included, regardless of
 * the requested day, so a technician never loses sight of live jobs on their list.
 */
@Service
@Transactional(readOnly = true)
public class TechnicianDayQueryService {

    private static final Logger log = LoggerFactory.getLogger(TechnicianDayQueryService.class);

    static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            "scheduledStart", "scheduledWindowStart",
            "priority",       "priority"
    );

    private static final Set<WorkOrderState> CARRY_OVER_STATES = Set.of(
            WorkOrderState.ASSIGNED,
            WorkOrderState.EN_ROUTE,
            WorkOrderState.IN_PROGRESS,
            WorkOrderState.ON_HOLD
    );

    private static final Set<WorkOrderState> TERMINAL_STATES = Set.of(
            WorkOrderState.COMPLETED,
            WorkOrderState.CLOSED,
            WorkOrderState.CANCELLED
    );

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final EntityManager entityManager;

    public TechnicianDayQueryService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            EntityManager entityManager) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
        this.entityManager = entityManager;
    }

    /**
     * Returns the technician day-list for the given date.
     *
     * @param date      the operating day (defaults to today UTC in the caller)
     * @param pageQuery pagination and sort parameters
     * @param request   current HTTP request for link generation
     * @return scoped, windowed page of compact job summaries
     */
    @PreAuthorize("hasAnyAuthority('TECHNICIAN','DISPATCHER','ADMIN')")
    public PagedResponse<TechnicianJobSummary> listJobs(
            LocalDate date,
            PageQuery pageQuery,
            HttpServletRequest request) {

        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Specification<WorkOrder> spec = buildDaySpec(dayStart, dayEnd);
        Sort sort = buildSort(pageQuery.sort());
        Pageable pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);

        Page<WorkOrder> page = scopedQueryExecutor.findAll(
                WorkOrder.class, spec, pageable, workOrderRepository);

        List<WorkOrder> content = page.getContent();

        // Batch-fetch assets by UUID to avoid N+1
        Map<UUID, Asset> assetMap = batchFetchAssets(
                content.stream()
                        .map(WorkOrder::getAssetId)
                        .filter(id -> id != null)
                        .distinct()
                        .toList());

        List<TechnicianJobSummary> rows = content.stream()
                .map(wo -> toSummary(wo, assetMap))
                .toList();

        long rowCount = page.getTotalElements();
        log.info("technician.day.query: date={} page={} size={} rows={}",
                date, pageQuery.page(), pageQuery.size(), rowCount);

        PageMeta meta = PageMeta.of(page.getNumber(), page.getSize(), rowCount);
        PageLinks links = buildOffsetLinks(request, page.getNumber(), page.getSize(),
                page.getTotalPages());
        return PagedResponse.of(rows, meta, links);
    }

    // ── Specification ─────────────────────────────────────────────────────────

    static Specification<WorkOrder> buildDaySpec(Instant dayStart, Instant dayEnd) {
        // Today's windowed jobs in an active state (terminal states are excluded)
        Specification<WorkOrder> todayJobs = (root, q, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("scheduledWindowStart"), dayStart),
                cb.lessThan(root.get("scheduledWindowStart"), dayEnd),
                root.get("state").in(CARRY_OVER_STATES)
        );

        // Carry-over: active state + scheduled before today (or no scheduled window)
        Specification<WorkOrder> carryOver = (root, q, cb) -> cb.and(
                root.get("state").in(CARRY_OVER_STATES),
                cb.or(
                        cb.isNull(root.get("scheduledWindowStart")),
                        cb.lessThan(root.get("scheduledWindowStart"), dayStart)
                )
        );

        return todayJobs.or(carryOver);
    }

    // ── Sort building ────────────────────────────────────────────────────────

    private static Sort buildSort(List<SortField> clientSort) {
        List<Sort.Order> orders = new ArrayList<>();

        if (clientSort.isEmpty()) {
            // Default: scheduled start ASC (nulls last), priority DESC, id ASC
            orders.add(Sort.Order.asc("scheduledWindowStart").with(Sort.NullHandling.NULLS_LAST));
            orders.add(Sort.Order.desc("priority"));
        } else {
            for (SortField sf : clientSort) {
                String persistent = SORT_ALLOW_LIST.resolvePersistentName(sf.field());
                Sort.Order order = sf.direction() == Sort.Direction.ASC
                        ? Sort.Order.asc(persistent)
                        : Sort.Order.desc(persistent);
                if ("scheduledWindowStart".equals(persistent) && sf.direction() == Sort.Direction.ASC) {
                    order = order.with(Sort.NullHandling.NULLS_LAST);
                }
                orders.add(order);
            }
        }

        // Mandatory id tie-break
        boolean hasId = orders.stream().anyMatch(o -> "id".equals(o.getProperty()));
        if (!hasId) {
            orders.add(Sort.Order.asc("id"));
        }

        return Sort.by(orders);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private static TechnicianJobSummary toSummary(WorkOrder wo, Map<UUID, Asset> assetMap) {
        var site    = wo.getSite();
        var customer = wo.getCustomer();
        Asset asset = wo.getAssetId() != null ? assetMap.get(wo.getAssetId()) : null;

        boolean atRisk = wo.getAtRiskAt() != null
                && wo.getAtRiskAt().isBefore(Instant.now())
                && !TERMINAL_STATES.contains(wo.getState());

        String rawPhone = customer != null ? customer.getContactPhone() : null;
        String maskedPhone = ContactMaskingHelper.maskPhone(rawPhone);

        return new TechnicianJobSummary(
                wo.getId(),
                wo.getReference(),
                wo.getPriority() != null ? wo.getPriority().name() : null,
                wo.getState() != null ? wo.getState().name() : null,
                wo.getScheduledWindowStart(),
                wo.getScheduledWindowEnd(),
                site != null ? site.getName() : null,
                site != null ? site.getAddress() : null,
                site != null ? site.getLatitude() : null,
                site != null ? site.getLongitude() : null,
                asset != null ? asset.getAssetTag() : null,
                asset != null ? asset.getName() : null,
                wo.getFaultDescription(),
                wo.getResolutionDueAt(),
                atRisk,
                maskedPhone,
                wo.getVersion()
        );
    }

    // ── Asset batch fetch ────────────────────────────────────────────────────

    private Map<UUID, Asset> batchFetchAssets(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Asset> assets = entityManager
                .createQuery("SELECT a FROM Asset a WHERE a.id IN :ids", Asset.class)
                .setParameter("ids", ids)
                .getResultList();
        return assets.stream().collect(Collectors.toMap(Asset::getId, a -> a));
    }

    // ── Link generation ──────────────────────────────────────────────────────

    private static PageLinks buildOffsetLinks(HttpServletRequest request,
                                              int currentPage, int size, int totalPages) {
        String next = (currentPage + 1 < totalPages)
                ? replacePageParam(request, currentPage + 1, size)
                : null;
        String prev = (currentPage > 0)
                ? replacePageParam(request, currentPage - 1, size)
                : null;
        return PageLinks.of(next, prev);
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequest(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }
}

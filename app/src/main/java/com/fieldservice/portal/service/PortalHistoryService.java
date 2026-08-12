package com.fieldservice.portal.service;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.asset.repository.AssetRepository;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.history.InvalidDateRangeException;
import com.fieldservice.portal.history.StatusGroup;
import com.fieldservice.portal.i18n.CustomerStateLabels;
import com.fieldservice.portal.web.dto.PortalHistoryRow;
import com.fieldservice.site.repository.SiteRepository;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Portal-facing service for the paginated customer service history collection.
 *
 * <p>Every query is scoped to the authenticated portal account via
 * {@link CustomerAccessScope#workOrderPredicate()}.  Optional filters for
 * site, status group, and date range are composed as additional Specification predicates.
 *
 * <h3>Scope enforcement</h3>
 * The scope predicate and all optional filters are evaluated in SQL. No post-query
 * filtering occurs in application memory. A foreign {@code siteId} returns 404
 * via {@link com.fieldservice.portal.access.ScopeUnavailableException} after an
 * ownership check — the error is indistinguishable from a non-existent resource.
 *
 * <h3>Date range</h3>
 * The span between {@code fromDate} and {@code toDate} is bounded to
 * {@value #MAX_DATE_RANGE_DAYS} days.  An inverted or over-wide range returns 400
 * via {@link InvalidDateRangeException}.
 */
@Service
public class PortalHistoryService {

    /** Hard limit on the requested date span — prevents unbounded scans. */
    static final int MAX_DATE_RANGE_DAYS = 365;

    private static final Set<WorkOrderStatus> TERMINAL_STATES = Set.of(
            WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED, WorkOrderStatus.CANCELLED);

    private final WorkOrderRepository workOrderRepository;
    private final CustomerAccessScope customerAccessScope;
    private final SiteRepository      siteRepository;
    private final AssetRepository     assetRepository;

    public PortalHistoryService(WorkOrderRepository workOrderRepository,
                                CustomerAccessScope customerAccessScope,
                                SiteRepository      siteRepository,
                                AssetRepository     assetRepository) {
        this.workOrderRepository = workOrderRepository;
        this.customerAccessScope = customerAccessScope;
        this.siteRepository      = siteRepository;
        this.assetRepository     = assetRepository;
    }

    /**
     * Returns a scoped, filtered, paginated page of history rows.
     *
     * @param pageable     page + size + sort (sort already validated by controller)
     * @param cursorSpec   optional keyset cursor predicate composed by the controller;
     *                     {@code null} for offset pagination
     * @param siteId       optional site filter; foreign siteId → 404
     * @param statusGroup  optional status-group filter (OPEN or CLOSED)
     * @param fromDate     optional lower bound on {@code created_at}
     * @param toDate       optional upper bound on {@code created_at}
     * @return paged projection of work order history rows
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Page<PortalHistoryRow> findHistory(
            Pageable pageable,
            Specification<WorkOrder> cursorSpec,
            UUID         siteId,
            StatusGroup  statusGroup,
            Instant      fromDate,
            Instant      toDate) {

        UUID accountId = customerAccessScope.resolveAccountId();

        validateDateRange(fromDate, toDate);
        validateSiteOwnership(siteId, accountId);

        Specification<WorkOrder> spec = buildSpec(accountId, siteId, statusGroup, fromDate, toDate);
        if (cursorSpec != null) {
            spec = spec.and(cursorSpec);
        }
        Page<WorkOrder> page = workOrderRepository.findAll(spec, pageable);

        // Batch-load referenced assets to avoid N+1
        Map<UUID, Asset> assetMap = loadAssets(page.getContent());

        return page.map(wo -> toRow(wo, assetMap));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Specification<WorkOrder> buildSpec(
            UUID accountId,
            UUID siteId,
            StatusGroup statusGroup,
            Instant fromDate,
            Instant toDate) {

        // Base scope predicate — restricts to this customer's account
        Specification<WorkOrder> spec = customerAccessScope.workOrderPredicate();

        if (siteId != null) {
            spec = spec.and(siteEqual(siteId));
        }
        if (statusGroup != null) {
            spec = spec.and(stateIn(statusGroup.getStates()));
        }
        if (fromDate != null) {
            spec = spec.and(createdAtGte(fromDate));
        }
        if (toDate != null) {
            spec = spec.and(createdAtLte(toDate));
        }

        return spec;
    }

    private void validateDateRange(Instant fromDate, Instant toDate) {
        if (fromDate == null || toDate == null) {
            // Partial range is fine — no validation needed
            return;
        }
        if (fromDate.isAfter(toDate)) {
            throw new InvalidDateRangeException("fromDate",
                    "fromDate must not be after toDate.");
        }
        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days > MAX_DATE_RANGE_DAYS) {
            throw new InvalidDateRangeException("toDate",
                    "Date range must not exceed " + MAX_DATE_RANGE_DAYS + " days.");
        }
    }

    /**
     * Verifies that a client-supplied siteId belongs to the resolved customer account.
     * A foreign siteId throws {@link com.fieldservice.portal.access.ScopeUnavailableException}
     * → 404, making it indistinguishable from a non-existent site.
     */
    private void validateSiteOwnership(UUID siteId, UUID accountId) {
        if (siteId == null) return;
        siteRepository.findById(siteId)
                .filter(s -> accountId.equals(s.getCustomerId()))
                .orElseThrow(() -> new com.fieldservice.portal.access.ScopeUnavailableException(
                        "Site not found or not accessible to this portal account"));
    }

    private Map<UUID, Asset> loadAssets(List<WorkOrder> workOrders) {
        List<UUID> assetIds = workOrders.stream()
                .map(WorkOrder::getAssetId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a));
    }

    private PortalHistoryRow toRow(WorkOrder wo, Map<UUID, Asset> assetMap) {
        CustomerStateLabels labels = CustomerStateLabels.resolve(wo.getState(), null);

        String assetLabel = null;
        if (wo.getAssetId() != null) {
            Asset asset = assetMap.get(wo.getAssetId());
            if (asset != null) {
                assetLabel = buildAssetLabel(asset);
            }
        }

        String outcomeSummary = TERMINAL_STATES.contains(wo.getState())
                ? labels.getDescription() : null;

        return new PortalHistoryRow(
                wo.getId(),
                wo.getReference(),
                labels.getLabel(),
                wo.getSite().getName(),
                assetLabel,
                wo.getCreatedAt(),
                null,          // closedAt: no closed_at column in current schema
                outcomeSummary);
    }

    private static String buildAssetLabel(Asset asset) {
        if (asset.getModel() != null && !asset.getModel().isBlank()) {
            return asset.getAssetTag() != null
                    ? asset.getModel() + " (" + asset.getAssetTag() + ")"
                    : asset.getModel();
        }
        if (asset.getAssetTag() != null) return asset.getAssetTag();
        if (asset.getSerialNumber() != null) return asset.getSerialNumber();
        return null;
    }

    // ---- Specification helpers ---------------------------------------------

    private static Specification<WorkOrder> siteEqual(UUID siteId) {
        return (root, q, cb) -> cb.equal(root.get("site").get("id"), siteId);
    }

    private static Specification<WorkOrder> stateIn(List<WorkOrderStatus> states) {
        return (root, q, cb) -> root.get("state").in(states);
    }

    private static Specification<WorkOrder> createdAtGte(Instant from) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    private static Specification<WorkOrder> createdAtLte(Instant to) {
        return (root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }
}

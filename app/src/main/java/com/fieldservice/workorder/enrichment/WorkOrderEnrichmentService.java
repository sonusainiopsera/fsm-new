package com.fieldservice.workorder.enrichment;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.asset.AssetRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implements {@link WorkOrderEnrichmentPort} by loading the work order, its asset,
 * customer and site via the existing scoped infrastructure, and querying prior closed
 * work orders on the same asset via JDBC.
 *
 * <p>Prior-work-order rows are scoped to the same {@code customer_id} as the target work
 * order. This prevents cross-account leakage while still surfacing full asset service
 * history within the customer's account — intentional for AI grounding. Row-scope
 * access control on the target work order itself is enforced by {@link ScopedQueryExecutor}.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('TECHNICIAN','ADMIN','DISPATCHER','MANAGER')")
class WorkOrderEnrichmentService implements WorkOrderEnrichmentPort {

    private static final String PRIOR_WO_SQL = """
            SELECT wo.id AS wo_id,
                   wo.fault_description,
                   wo.description AS resolution_notes
            FROM   work_order wo
            WHERE  wo.asset_id   = ?
              AND  wo.customer_id = ?
              AND  wo.id         != ?
              AND  wo.state      IN ('COMPLETED','CLOSED')
            ORDER  BY wo.updated_at DESC, wo.id
            LIMIT  ?
            """;

    private static final String PARTS_SQL = """
            SELECT p.description
            FROM   work_order_part_consumption wpc
            JOIN   part p ON p.id = wpc.part_id
            WHERE  wpc.work_order_id = ?
            ORDER  BY p.description
            """;

    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderRepository workOrderRepository;
    private final AssetRepository assetRepository;
    private final JdbcTemplate jdbc;

    WorkOrderEnrichmentService(
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderRepository workOrderRepository,
            AssetRepository assetRepository,
            JdbcTemplate jdbc) {
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.workOrderRepository = workOrderRepository;
        this.assetRepository     = assetRepository;
        this.jdbc                = jdbc;
    }

    @Override
    public WorkOrderEnrichmentContext loadContext(UUID workOrderId, int maxPriorWorkOrders) {
        // Scope-enforced load — out-of-scope or missing WO throws ScopedAccessDeniedException
        WorkOrder wo = scopedQueryExecutor.findById(WorkOrder.class, workOrderId, workOrderRepository);

        // Trigger lazy loads within transaction — no extra queries if already cached by Hibernate
        var customer = wo.getCustomer();
        var site     = wo.getSite();

        Asset asset = null;
        List<PriorWorkOrderSummary> prior = List.of();

        UUID assetId = wo.getAssetId();
        if (assetId != null) {
            asset = scopedQueryExecutor.findById(Asset.class, assetId, assetRepository);
            prior = loadPriorWorkOrders(assetId, wo.getCustomerId(), workOrderId, maxPriorWorkOrders);
        }

        return new WorkOrderEnrichmentContext(
                workOrderId,
                wo.getFaultDescription(),
                asset,
                site,
                customer,
                prior);
    }

    private List<PriorWorkOrderSummary> loadPriorWorkOrders(
            UUID assetId, UUID customerId, UUID excludeWorkOrderId, int limit) {

        return jdbc.query(PRIOR_WO_SQL,
                (rs, rowNum) -> {
                    UUID woId = rs.getObject("wo_id", UUID.class);
                    List<String> parts = jdbc.queryForList(PARTS_SQL, String.class, woId);
                    return new PriorWorkOrderSummary(
                            woId,
                            rs.getString("fault_description"),
                            rs.getString("resolution_notes"),
                            parts);
                },
                assetId, customerId, excludeWorkOrderId, limit);
    }
}

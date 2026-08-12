package com.fieldservice.asset.web;

import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Returns the prior service history for a given asset.
 *
 * <p>Scoped: only work orders assigned to the authenticated technician are returned
 * (technician scope), ensuring no existence disclosure. The result is truncated to
 * the last {@code limit} closed work orders (max 10, default 5).
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetServiceHistoryController {

    private static final int MAX_HISTORY_SIZE = 10;
    private static final int DEFAULT_HISTORY_SIZE = 5;

    private final JdbcTemplate            jdbc;
    private final RequestScopedAccessScope accessScope;

    public AssetServiceHistoryController(JdbcTemplate jdbc,
                                         RequestScopedAccessScope accessScope) {
        this.jdbc        = jdbc;
        this.accessScope = accessScope;
    }

    /**
     * Returns the last closed work orders for {@code assetId}, scoped to the caller.
     *
     * @param assetId the asset UUID
     * @param limit   max results; capped at 10, defaults to 5
     * @return list of closed WO summaries in reverse chronological order
     */
    @GetMapping("/{assetId}/service-history")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<List<AssetServiceRecord>> getServiceHistory(
            @PathVariable UUID assetId,
            @RequestParam(defaultValue = "5") int limit) {

        int cappedLimit = Math.min(Math.max(1, limit), MAX_HISTORY_SIZE);
        UUID technicianId = accessScope.get().technicianId();

        String sql =
            "SELECT wo.id, wo.reference, wo.description AS fault_summary, " +
            "  wo.resolution_deadline AS resolved_at, wo.fault_code, wo.fault_category " +
            "FROM work_order wo " +
            "WHERE wo.asset_id = ? " +
            "  AND wo.state IN ('COMPLETED', 'CLOSED') " +
            "  AND (wo.assigned_technician_id = ? OR ? IS NULL) " +
            "ORDER BY COALESCE(wo.resolution_deadline, wo.created_at) DESC " +
            "LIMIT ?";

        List<AssetServiceRecord> history = jdbc.query(
            sql,
            new Object[]{assetId, technicianId, technicianId, cappedLimit},
            (rs, rowNum) -> new AssetServiceRecord(
                    (UUID) rs.getObject("id"),
                    rs.getString("reference"),
                    rs.getString("fault_summary"),
                    rs.getTimestamp("resolved_at") != null
                            ? rs.getTimestamp("resolved_at").toInstant() : null,
                    rs.getString("fault_code"),
                    rs.getString("fault_category")));

        return ResponseEntity.ok(history);
    }

    /** Compact prior-service summary returned to technician clients. */
    public record AssetServiceRecord(
            UUID   id,
            String reference,
            String faultSummary,
            Instant resolvedAt,
            String faultCode,
            String faultCategory
    ) {}
}

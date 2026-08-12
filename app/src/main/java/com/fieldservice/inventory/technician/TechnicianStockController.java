package com.fieldservice.inventory.technician;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Returns the authenticated technician's own vehicle stock lines.
 *
 * <p>Scoped to the caller's technician_id via a JOIN to stock_location — no other
 * technician's vehicle is ever returned. Only VEHICLE-type locations are included.
 *
 * <p>Designed for the log-work screen parts selection list; includes on-hand quantity
 * so the UI can enforce upper bounds before posting.
 */
@RestController
@RequestMapping("/api/v1/technicians/me")
public class TechnicianStockController {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE     = 100;

    private final JdbcTemplate            jdbc;
    private final RequestScopedAccessScope accessScope;

    public TechnicianStockController(JdbcTemplate jdbc, RequestScopedAccessScope accessScope) {
        this.jdbc        = jdbc;
        this.accessScope = accessScope;
    }

    /**
     * Lists parts currently in the authenticated technician's vehicle stock.
     *
     * @param page 0-based page index (default 0)
     * @param size page size, capped at 100 (default 50)
     * @return paginated list of vehicle stock lines with on-hand quantities
     */
    @GetMapping("/stock")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<PagedResponse<VehicleStockLine>> getMyStock(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        UUID technicianId = accessScope.get().technicianId();
        if (technicianId == null) {
            return ResponseEntity.ok(PagedResponse.empty(DEFAULT_SIZE));
        }

        int cappedSize = Math.min(Math.max(1, size), MAX_SIZE);
        int offset     = Math.max(0, page) * cappedSize;

        String countSql =
            "SELECT COUNT(*) FROM stock_balance sb " +
            "JOIN stock_location sl ON sl.id = sb.location_id " +
            "JOIN part p ON p.id = sb.part_id " +
            "WHERE sl.technician_id = ? AND sl.location_type = 'VEHICLE' " +
            "  AND p.active = TRUE AND sb.quantity_on_hand > 0";

        long totalElements = jdbc.queryForObject(countSql, Long.class, technicianId);

        if (totalElements == 0) {
            return ResponseEntity.ok(PagedResponse.empty(cappedSize));
        }

        String dataSql =
            "SELECT p.id AS part_id, p.part_number, p.name AS part_name, " +
            "  p.description, p.unit_of_measure, " +
            "  sb.quantity_on_hand, sl.id AS location_id " +
            "FROM stock_balance sb " +
            "JOIN stock_location sl ON sl.id = sb.location_id " +
            "JOIN part p ON p.id = sb.part_id " +
            "WHERE sl.technician_id = ? AND sl.location_type = 'VEHICLE' " +
            "  AND p.active = TRUE AND sb.quantity_on_hand > 0 " +
            "ORDER BY p.part_number ASC " +
            "LIMIT ? OFFSET ?";

        List<VehicleStockLine> lines = jdbc.query(dataSql,
            new Object[]{technicianId, cappedSize, offset},
            (rs, rowNum) -> new VehicleStockLine(
                    (UUID) rs.getObject("part_id"),
                    rs.getString("part_number"),
                    rs.getString("part_name"),
                    rs.getString("description"),
                    rs.getString("unit_of_measure"),
                    rs.getInt("quantity_on_hand"),
                    (UUID) rs.getObject("location_id")));

        PageMeta meta   = PageMeta.of(page, cappedSize, totalElements);
        int totalPages  = meta.totalPages();
        String next = (page + 1 < totalPages) ? "/api/v1/technicians/me/stock?page=" + (page + 1) + "&size=" + cappedSize : null;
        String prev = (page > 0) ? "/api/v1/technicians/me/stock?page=" + (page - 1) + "&size=" + cappedSize : null;
        PageLinks links = PageLinks.of(next, prev);

        return ResponseEntity.ok(PagedResponse.of(lines, meta, links));
    }

    /** A single vehicle stock line for the mobile log-work parts picker. */
    public record VehicleStockLine(
            UUID   partId,
            String partCode,
            String partName,
            String description,
            String unitOfMeasure,
            int    quantityOnHand,
            UUID   locationId
    ) {}
}

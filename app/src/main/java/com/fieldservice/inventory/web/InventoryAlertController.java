package com.fieldservice.inventory.web;

import com.fieldservice.inventory.domain.StockAlert;
import com.fieldservice.inventory.repository.StockAlertRepository;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only endpoint for active inventory stock alerts.
 *
 * <p>Provides the human-visible destination for replenishment signals
 * and threshold-based alerts. Restricted to DISPATCHER and ADMIN roles;
 * technicians and customers are explicitly denied.
 *
 * <p>GET /api/v1/inventory/alerts?state=ACTIVE&page=0&size=20
 */
@RestController
@RequestMapping("/api/v1/inventory/alerts")
@PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
public class InventoryAlertController {

    private final StockAlertRepository alertRepository;

    public InventoryAlertController(StockAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<AlertDto>> listAlerts(
            @RequestParam(name = "state", defaultValue = "ACTIVE") String state,
            @RequestParam(name = "page",  defaultValue = "0")      int page,
            @RequestParam(name = "size",  defaultValue = "20")     int size) {

        StockAlert.AlertState alertState = "ACTIVE".equalsIgnoreCase(state)
                ? StockAlert.AlertState.OPEN
                : StockAlert.AlertState.CLEARED;

        Page<StockAlert> results = alertRepository.findByState(
                alertState,
                PageRequest.of(page, Math.min(size, 100), Sort.by("raisedAt").descending()));

        List<AlertDto> items = results.getContent().stream()
                .map(AlertDto::from)
                .toList();

        PageMeta meta = new PageMeta(results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages(), results.hasNext());

        return ResponseEntity.ok(new PagedResponse<>(items, meta));
    }

    /** DTO projected from {@link StockAlert} for the paginated response. */
    public record AlertDto(
            UUID    alertId,
            UUID    partId,
            UUID    stockLocationId,
            String  alertType,
            String  state,
            Instant raisedAt,
            Instant lastNotifiedAt,
            UUID    workOrderId) {

        static AlertDto from(StockAlert alert) {
            return new AlertDto(
                    alert.getId(),
                    alert.getPartId(),
                    alert.getStockLocationId(),
                    alert.getAlertType().name(),
                    alert.getState().name(),
                    alert.getRaisedAt(),
                    alert.getLastNotifiedAt(),
                    alert.getWorkOrderId());
        }
    }
}

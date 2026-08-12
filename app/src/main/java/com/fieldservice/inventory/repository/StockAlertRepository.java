package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.StockAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, UUID> {

    /**
     * Finds the single open alert for the given (part, location, type) combination.
     * Used by the evaluator for deduplication — at most one OPEN row per combination
     * is guaranteed by the unique partial index.
     */
    @Query("SELECT a FROM StockAlert a WHERE a.partId = :partId " +
           "AND a.stockLocationId = :locationId " +
           "AND a.alertType = :alertType " +
           "AND a.state = com.fieldservice.inventory.domain.StockAlert.AlertState.OPEN")
    Optional<StockAlert> findOpenAlert(
            @Param("partId") UUID partId,
            @Param("locationId") UUID locationId,
            @Param("alertType") StockAlert.AlertType alertType);

    /**
     * Paginated read of all OPEN alerts for the active-alerts endpoint (GET /inventory/alerts).
     */
    Page<StockAlert> findByState(StockAlert.AlertState state, Pageable pageable);

    /** Used to verify no duplicate open alert exists in tests. */
    long countByPartIdAndStockLocationIdAndAlertTypeAndState(
            UUID partId, UUID locationId,
            StockAlert.AlertType alertType, StockAlert.AlertState state);
}

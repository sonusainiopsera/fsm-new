package com.fieldservice.domain.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockAlertRepository extends JpaRepository<StockAlert, UUID> {

    /**
     * Returns the single active alert for (partId, locationId, alertType), or empty if none.
     * The partial unique index guarantees at most one row matches.
     */
    @Query("SELECT a FROM StockAlert a " +
           "WHERE a.partId = :partId AND a.stockLocationId = :locationId " +
           "  AND a.alertType = :alertType AND a.state = com.fieldservice.domain.inventory.StockAlert.AlertState.ACTIVE")
    Optional<StockAlert> findActiveAlert(
            @Param("partId")    UUID partId,
            @Param("locationId") UUID locationId,
            @Param("alertType") StockAlert.AlertType alertType);

    /** Returns paginated alerts filtered by state (for the read endpoint). */
    Page<StockAlert> findByState(StockAlert.AlertState state, Pageable pageable);

    /** Counts alerts by state (used by Micrometer gauges). */
    long countByState(StockAlert.AlertState state);
}

package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.StockBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockBalanceRepository
        extends JpaRepository<StockBalance, UUID>, JpaSpecificationExecutor<StockBalance> {

    Optional<StockBalance> findByPartIdAndLocationId(UUID partId, UUID locationId);

    List<StockBalance> findByLocationId(UUID locationId);

    /**
     * Conditionally decrements quantity_on_hand by {@code qty} only when the balance is
     * sufficient (quantity_on_hand >= qty). Returns the number of rows updated (0 or 1).
     *
     * <p>Uses {@code clearAutomatically = true} and {@code flushAutomatically = true} so the
     * persistence context cannot serve a stale balance after the conditional update completes.
     *
     * <p>No isolation escalation, no pessimistic lock, no retry loop — the conditional WHERE
     * clause is the sole enforcement mechanism per architecture BR-16.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockBalance sb " +
           "SET sb.quantityOnHand = sb.quantityOnHand - :qty " +
           "WHERE sb.partId = :partId AND sb.locationId = :locationId " +
           "AND sb.quantityOnHand >= :qty")
    int conditionalDecrement(@Param("partId")     UUID partId,
                             @Param("locationId") UUID locationId,
                             @Param("qty")        int  qty);

    /**
     * Unconditionally increments quantity_on_hand by {@code qty} (used for returns).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockBalance sb " +
           "SET sb.quantityOnHand = sb.quantityOnHand + :qty " +
           "WHERE sb.partId = :partId AND sb.locationId = :locationId")
    int increment(@Param("partId")     UUID partId,
                  @Param("locationId") UUID locationId,
                  @Param("qty")        int  qty);
}

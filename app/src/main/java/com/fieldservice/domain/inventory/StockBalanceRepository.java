package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockBalanceRepository extends ScopedRepository<StockBalance, UUID> {

    Optional<StockBalance> findByPartIdAndLocationId(UUID partId, UUID locationId);

    /**
     * Atomically decrements stock if sufficient quantity is available.
     *
     * <p>Uses a conditional UPDATE with {@code quantity_on_hand >= :qty} predicate so zero
     * affected rows means the balance was insufficient — no isolation escalation, pessimistic
     * locking, or retry loop is used (WO-149 mandatory constraint).
     *
     * @return 1 if the decrement succeeded; 0 if insufficient stock
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockBalance s SET s.quantityOnHand = s.quantityOnHand - :qty, " +
           "s.version = s.version + 1 " +
           "WHERE s.partId = :partId AND s.locationId = :locationId AND s.quantityOnHand >= :qty")
    int conditionalDecrement(@Param("partId") UUID partId,
                             @Param("locationId") UUID locationId,
                             @Param("qty") int qty);

    /**
     * Atomically increments stock (used for returns and transfers).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockBalance s SET s.quantityOnHand = s.quantityOnHand + :qty, " +
           "s.version = s.version + 1 " +
           "WHERE s.partId = :partId AND s.locationId = :locationId")
    int increment(@Param("partId") UUID partId,
                  @Param("locationId") UUID locationId,
                  @Param("qty") int qty);
}

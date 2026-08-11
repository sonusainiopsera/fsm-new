package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.Optional;
import java.util.UUID;

public interface StockBalanceRepository extends ScopedRepository<StockBalance, UUID> {

    Optional<StockBalance> findByPartIdAndLocationId(UUID partId, UUID locationId);
}

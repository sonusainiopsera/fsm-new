package com.fieldservice.domain.stockmovement;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface StockMovementRepository extends ScopedRepository<StockMovement, UUID> {
}

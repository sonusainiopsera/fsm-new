package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface StockLocationRepository extends ScopedRepository<StockLocation, UUID> {}

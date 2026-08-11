package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

public interface StockBalanceRepository extends ScopedRepository<StockBalance, UUID> {
}

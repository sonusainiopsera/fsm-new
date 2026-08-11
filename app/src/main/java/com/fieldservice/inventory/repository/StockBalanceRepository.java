package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.StockBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface StockBalanceRepository
        extends JpaRepository<StockBalance, UUID>, JpaSpecificationExecutor<StockBalance> {
}

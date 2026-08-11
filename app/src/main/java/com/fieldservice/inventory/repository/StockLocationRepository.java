package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockLocationRepository extends ScopedRepository<StockLocation, UUID> {
    Optional<StockLocation> findByTechnicianId(UUID technicianId);
}

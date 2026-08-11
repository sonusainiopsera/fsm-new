package com.fieldservice.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Repository for stock locations.
 *
 * <p>Stock locations are not row-scoped entities. Scope enforcement is applied at the
 * service layer via {@link org.springframework.security.access.prepost.PreAuthorize}.
 */
public interface StockLocationRepository extends JpaRepository<StockLocation, UUID>, JpaSpecificationExecutor<StockLocation> {
}

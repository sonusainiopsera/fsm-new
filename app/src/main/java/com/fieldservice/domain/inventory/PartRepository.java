package com.fieldservice.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Repository for the parts catalog.
 *
 * <p>Part is reference data, not a row-scoped entity. Scope enforcement is applied
 * at the service layer via {@link org.springframework.security.access.prepost.PreAuthorize}.
 * CUSTOMER access is denied outright; all other roles receive the full catalog.
 */
public interface PartRepository extends JpaRepository<Part, UUID>, JpaSpecificationExecutor<Part> {
}

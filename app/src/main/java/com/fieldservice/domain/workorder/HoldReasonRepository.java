package com.fieldservice.domain.workorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Non-scoped repository for hold reason reference data.
 *
 * <p>Not a {@link com.fieldservice.platform.persistence.ScopedRepository} because
 * hold reasons are global reference data with no per-tenant row scope.
 */
public interface HoldReasonRepository extends JpaRepository<HoldReason, String> {

    List<HoldReason> findByActiveTrueOrderBySortOrderAsc();
}

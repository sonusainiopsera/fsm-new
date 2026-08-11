package com.fieldservice.privacy.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository for {@link PurgeRun}.
 *
 * <p>Package-private — append-only. Only {@code save()} (inherited from
 * {@link JpaRepository}) and read methods are used; no custom update or delete
 * methods are defined, reinforcing the append-only constraint at the API level.
 *
 * <p>Access is controlled at the service layer; purge_run rows are written only
 * by {@code PurgeSweepJob} and read-only from the admin API.
 */
interface PurgeRunRepository extends JpaRepository<PurgeRun, UUID> {

    Page<PurgeRun> findByDataCategoryOrderByStartedAtDesc(String dataCategory, Pageable pageable);
}

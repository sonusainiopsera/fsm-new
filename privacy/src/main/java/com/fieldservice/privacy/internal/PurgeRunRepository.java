package com.fieldservice.privacy.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Append-only repository for {@link PurgeRunEntity}.
 *
 * <p>No deleteBy*, deleteAll, or update methods are exposed — purge_run rows are
 * immutable audit records.  The only write operation is {@code save()} for new rows.
 */
interface PurgeRunRepository extends JpaRepository<PurgeRunEntity, UUID> {

    List<PurgeRunEntity> findByDataCategoryOrderByStartedAtDesc(String dataCategory);

    Page<PurgeRunEntity> findByDataCategory(String dataCategory, Pageable pageable);
}

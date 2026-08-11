package com.fieldservice.analytics.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link KpiProjectionEntity}.
 *
 * <p>Package-private. All public access goes through
 * {@link com.fieldservice.analytics.KpiProjectionQuery}.
 *
 * <p>Not a {@link com.fieldservice.platform.persistence.ScopedRepository} because KPI
 * projections are aggregate analytics rows with no per-user row scope — access is
 * controlled at the API layer by role, not by row ownership.
 */
interface KpiProjectionRepository extends JpaRepository<KpiProjectionEntity, UUID> {

    Optional<KpiProjectionEntity> findByMetricKeyAndSegmentKeyAndWindowKey(
            String metricKey, String segmentKey, String windowKey);

    List<KpiProjectionEntity> findAllByMetricKey(String metricKey);

    @Query("SELECT e FROM KpiProjectionEntity e WHERE e.degraded = true")
    List<KpiProjectionEntity> findAllDegraded();
}

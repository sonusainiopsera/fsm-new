package com.fieldservice.analytics.internal.sla;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link BaselineMetricEntity}.
 * Package-private — callers use {@link SlaComplianceCalculator} and
 * {@link ResolutionTimeCalculator} which resolve baselines internally.
 */
public interface BaselineMetricRepository extends JpaRepository<BaselineMetricEntity, UUID> {

    Optional<BaselineMetricEntity> findByMetricKeyAndSegmentKey(String metricKey, String segmentKey);
}

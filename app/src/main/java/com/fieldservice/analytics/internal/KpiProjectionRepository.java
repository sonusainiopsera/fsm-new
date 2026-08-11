package com.fieldservice.analytics.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link KpiProjectionEntity}.
 *
 * <p>Package-private: external consumers use {@link com.fieldservice.analytics.KpiProjectionQuery}.
 * All write paths use parameterized JPQL or native queries (policy A05 — no string concatenation).
 */
interface KpiProjectionRepository extends JpaRepository<KpiProjectionEntity, UUID> {

    Optional<KpiProjectionEntity> findByMetricKeyAndSegmentKeyAndWindowKey(
            String metricKey, String segmentKey, String windowKey);

    List<KpiProjectionEntity> findByMetricKey(String metricKey);

    /**
     * Upserts a KPI projection row. Uses server-side version increment and a
     * data_as_of guard so an out-of-order older projection cannot overwrite a newer one.
     *
     * <p>Named parameterized query — no dynamic SQL, satisfies policy A05.
     */
    @Modifying
    @Query(value = """
            INSERT INTO kpi_projection
                (id, metric_key, segment_key, window_key,
                 numerator, denominator, value, sample_count,
                 maturity, data_as_of, projection_version, degraded)
            VALUES
                (:id, :metricKey, :segmentKey, :windowKey,
                 :numerator, :denominator, :value, :sampleCount,
                 :maturity, :dataAsOf, 1, :degraded)
            ON CONFLICT (metric_key, segment_key, window_key) DO UPDATE
               SET numerator          = EXCLUDED.numerator,
                   denominator        = EXCLUDED.denominator,
                   value              = EXCLUDED.value,
                   sample_count       = EXCLUDED.sample_count,
                   maturity           = EXCLUDED.maturity,
                   data_as_of         = EXCLUDED.data_as_of,
                   projection_version = kpi_projection.projection_version + 1,
                   degraded           = EXCLUDED.degraded
             WHERE kpi_projection.data_as_of <= EXCLUDED.data_as_of
            """, nativeQuery = true)
    void upsert(@Param("id")          UUID       id,
                @Param("metricKey")   String     metricKey,
                @Param("segmentKey")  String     segmentKey,
                @Param("windowKey")   String     windowKey,
                @Param("numerator")   BigDecimal numerator,
                @Param("denominator") BigDecimal denominator,
                @Param("value")       BigDecimal value,
                @Param("sampleCount") Integer    sampleCount,
                @Param("maturity")    String     maturity,
                @Param("dataAsOf")    Instant    dataAsOf,
                @Param("degraded")    boolean    degraded);

    /**
     * Staleness gauge support: returns data_as_of for all current projections
     * tagged by metric_key.
     */
    @Query("SELECT e FROM KpiProjectionEntity e ORDER BY e.dataAsOf ASC")
    List<KpiProjectionEntity> findAllOrderByDataAsOfAsc();
}

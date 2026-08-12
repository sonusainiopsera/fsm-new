package com.fieldservice.portal.csat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CsatSurveyRepository extends JpaRepository<CsatSurvey, UUID> {

    boolean existsBySourceEventId(UUID sourceEventId);

    boolean existsByWorkOrderId(UUID workOrderId);

    Optional<CsatSurvey> findByWorkOrderId(UUID workOrderId);

    Page<CsatSurvey> findByAccountIdOrderByIssuedAtDesc(UUID accountId, Pageable pageable);

    /**
     * Rolling 90-day mean CSAT score.
     * Returns null when no responses exist in the window (avoids divide-by-zero).
     *
     * <p>Formula: AVG(score) over submitted responses in the rolling window.
     */
    @Query("SELECT AVG(CAST(r.score AS double)) FROM CsatResponse r " +
           "JOIN CsatSurvey s ON r.surveyId = s.id " +
           "WHERE r.submittedAt >= :windowStart")
    Double rollingMeanScore(@Param("windowStart") Instant windowStart);

    /**
     * Returns the customer account UUID for the given work order by joining to the site table.
     * Used by the issuance consumer so it does not need to depend on WorkOrderRepository.
     */
    @Query(value = "SELECT s.customer_id FROM work_order wo "
                 + "JOIN site s ON wo.site_id = s.id "
                 + "WHERE wo.id = :workOrderId",
           nativeQuery = true)
    java.util.Optional<UUID> findAccountIdByWorkOrderId(@Param("workOrderId") UUID workOrderId);

    /**
     * Rolling 90-day response rate.
     *
     * <p>Denominator definition: issued surveys whose {@code expires_at} is after
     * {@code windowStart} — i.e. non-expired-at-window-start surveys only.
     * Excludes surveys that expired before the window opened (they were never actionable
     * in the window and would unfairly depress the rate).
     *
     * <p>Formula: responses / non-expired issued surveys in window. Returns null when
     * denominator is zero to prevent division-by-zero and signal "insufficient data".
     */
    @Query("SELECT " +
           "CASE WHEN COUNT(s.id) = 0 THEN NULL " +
           "ELSE CAST(COUNT(r.id) AS double) / CAST(COUNT(s.id) AS double) END " +
           "FROM CsatSurvey s " +
           "LEFT JOIN CsatResponse r ON r.surveyId = s.id " +
           "WHERE s.issuedAt >= :windowStart AND s.expiresAt >= :windowStart")
    Double rollingResponseRate(@Param("windowStart") Instant windowStart);
}

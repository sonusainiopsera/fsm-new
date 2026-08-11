package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface TechnicianCertificationRepository extends JpaRepository<TechnicianCertificationEntity, UUID> {

    /** All active certifications for a technician (currency evaluated by caller). */
    List<TechnicianCertificationEntity> findByTechnicianIdAndActiveTrue(UUID technicianId);

    /**
     * Active certifications that are current at {@code atDate}: {@code expires_on IS NULL OR expires_on >= atDate}.
     * Evaluates currency in SQL — expired rows are never loaded.
     */
    @Query("""
            SELECT tc FROM TechnicianCertificationEntity tc
              JOIN FETCH tc.certificationType ct
             WHERE tc.technicianId = :technicianId
               AND tc.active = true
               AND ct.active = true
               AND (tc.expiresOn IS NULL OR tc.expiresOn >= :atDate)
            """)
    List<TechnicianCertificationEntity> findCurrentCertifications(
            @Param("technicianId") UUID technicianId,
            @Param("atDate") LocalDate atDate);

    /**
     * Find existing active cert for a technician + type (for upsert dedup).
     */
    @Query("""
            SELECT tc FROM TechnicianCertificationEntity tc
             WHERE tc.technicianId = :technicianId
               AND tc.certificationType.id = :typeId
               AND tc.active = true
            """)
    Optional<TechnicianCertificationEntity> findActiveByTechnicianAndType(
            @Param("technicianId") UUID technicianId,
            @Param("typeId") UUID typeId);

    /**
     * Returns ALL technician IDs that hold a current certification for ALL codes in
     * {@code requiredCodes} at {@code atDate} — no candidate filter.
     *
     * <p>One round-trip: grouped query with HAVING COUNT(DISTINCT) = requiredCount.
     * Inactive technicians are excluded via the JOIN to the technician table.
     */
    @Query(value = """
            SELECT tc.technician_id
              FROM technician_certification tc
              JOIN technician t   ON t.id = tc.technician_id AND t.is_active = true
              JOIN certification_type ct ON ct.id = tc.certification_type_id
             WHERE ct.code IN :requiredCodes
               AND ct.active = true
               AND tc.active = true
               AND (tc.expires_on IS NULL OR tc.expires_on >= :atDate)
          GROUP BY tc.technician_id
            HAVING COUNT(DISTINCT ct.code) = :requiredCount
            """, nativeQuery = true)
    Set<UUID> findTechnicianIdsWithAllCurrentCertifications(
            @Param("requiredCodes") Set<String> requiredCodes,
            @Param("atDate") LocalDate atDate,
            @Param("requiredCount") long requiredCount);
}

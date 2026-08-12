package com.fieldservice.technician.repository;

import com.fieldservice.technician.domain.TechnicianCertification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TechnicianCertificationRepository extends JpaRepository<TechnicianCertification, UUID> {

    // ---- Legacy (V1 schema) — used by CertificationCurrencyGuard ---------------

    List<TechnicianCertification> findByTechnicianId(UUID technicianId);

    // ---- V39 new-schema queries ------------------------------------------------

    /**
     * Returns all active new-schema certifications for a technician,
     * evaluated against {@code atDate}: expires_on is null OR expires_on >= atDate.
     *
     * <p>Currency is evaluated here as a SQL predicate — never as a stored flag.
     */
    @Query("SELECT tc FROM TechnicianCertification tc " +
           "WHERE tc.technicianId = :technicianId " +
           "  AND tc.certificationTypeId IS NOT NULL " +
           "  AND tc.active = true " +
           "  AND (tc.expiresOn IS NULL OR tc.expiresOn >= :atDate)")
    List<TechnicianCertification> findCurrentByTechnicianId(
            @Param("technicianId") UUID technicianId,
            @Param("atDate") LocalDate atDate);

    /**
     * Returns all active certifications for a technician regardless of currency.
     * Used to determine whether an expiry is present (expired vs. absent).
     */
    @Query("SELECT tc FROM TechnicianCertification tc " +
           "WHERE tc.technicianId = :technicianId " +
           "  AND tc.certificationTypeId = :certificationTypeId " +
           "  AND tc.active = true " +
           "ORDER BY tc.expiresOn DESC NULLS FIRST")
    List<TechnicianCertification> findByTechnicianIdAndCertificationTypeId(
            @Param("technicianId") UUID technicianId,
            @Param("certificationTypeId") UUID certificationTypeId);

    /**
     * Bulk eligibility query: returns technician IDs that hold a current certification
     * for every required type code.
     *
     * <p>Implemented as a single grouped SQL query with HAVING COUNT(DISTINCT ...) so
     * dispatch candidate filtering is one round-trip.  Inactive technicians are excluded.
     *
     * <p>Currency predicate: expires_on IS NULL OR expires_on >= :atDate.
     */
    @Query(value = """
            SELECT tc.technician_id
            FROM technician_certification tc
            JOIN certification_type ct ON ct.id = tc.certification_type_id
            JOIN technician t          ON t.id  = tc.technician_id
            WHERE ct.code IN (:codes)
              AND ct.active = TRUE
              AND tc.active = TRUE
              AND t.active  = TRUE
              AND (tc.expires_on IS NULL OR tc.expires_on >= :atDate)
            GROUP BY tc.technician_id
            HAVING COUNT(DISTINCT ct.code) = :requiredCount
            """,
           nativeQuery = true)
    List<UUID> findTechnicianIdsWithCurrentCertifications(
            @Param("codes")         Set<String> codes,
            @Param("atDate")        LocalDate atDate,
            @Param("requiredCount") long requiredCount);

    /**
     * Checks whether a specific technician holds a current certification of the
     * given type code.  Returns true when at least one matching active row exists.
     */
    @Query(value = """
            SELECT COUNT(*) > 0
            FROM technician_certification tc
            JOIN certification_type ct ON ct.id = tc.certification_type_id
            WHERE tc.technician_id = :technicianId
              AND ct.code  = :code
              AND ct.active = TRUE
              AND tc.active = TRUE
              AND (tc.expires_on IS NULL OR tc.expires_on >= :atDate)
            """,
           nativeQuery = true)
    boolean existsCurrentByTechnicianIdAndTypeCode(
            @Param("technicianId") UUID technicianId,
            @Param("code")         String code,
            @Param("atDate")       LocalDate atDate);

    Optional<TechnicianCertification> findByTechnicianIdAndCertificationTypeIdAndActiveTrue(
            UUID technicianId, UUID certificationTypeId);
}

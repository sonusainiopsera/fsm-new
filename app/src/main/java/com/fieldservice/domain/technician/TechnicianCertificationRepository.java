package com.fieldservice.domain.technician;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read/write access to technician certifications.
 *
 * <p>Not a scoped repository: certification data is internal operational data used by
 * the dispatch feasibility gate (BR-01). Access is controlled at the service layer.
 */
public interface TechnicianCertificationRepository extends JpaRepository<TechnicianCertification, UUID> {

    /**
     * Returns all active (non-revoked) certifications for a technician that are valid
     * at the given instant. A null {@code expiresAt} is treated as never-expiring.
     * A cert expiring exactly at {@code now} is treated as expired (strict less-than).
     */
    @Query("SELECT c FROM TechnicianCertification c " +
           "WHERE c.technicianId = :technicianId " +
           "  AND c.revoked = false " +
           "  AND (c.expiresAt IS NULL OR c.expiresAt > :now)")
    List<TechnicianCertification> findActiveCertificationsAt(
            @Param("technicianId") UUID technicianId,
            @Param("now") Instant now);
}

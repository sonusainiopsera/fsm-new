package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface TechnicianPositionRepository extends JpaRepository<TechnicianPositionEntity, UUID> {

    @Query("SELECT p FROM TechnicianPositionEntity p WHERE p.technicianId = :technicianId " +
           "ORDER BY p.capturedAt DESC")
    Optional<TechnicianPositionEntity> findLatestByTechnicianId(@Param("technicianId") UUID technicianId);

    @Modifying
    @Query("DELETE FROM TechnicianPositionEntity p WHERE p.capturedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CertificationAlertStateRepository extends JpaRepository<CertificationAlertStateEntity, UUID> {

    /**
     * Returns {@code true} when this (certificationId, stage, validityKey) triple has
     * already been alerted. Used for in-memory de-duplication before attempting the insert.
     */
    boolean existsByTechnicianCertificationIdAndAlertStageAndValidityKey(
            UUID technicianCertificationId,
            CertificationAlertStage alertStage,
            String validityKey);
}

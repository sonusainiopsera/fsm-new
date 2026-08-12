package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CertificationAlertStateRepository extends JpaRepository<CertificationAlertStateEntity, UUID> {

    Optional<CertificationAlertStateEntity> findByTechnicianCertificationIdAndAlertStageAndValidityKey(
            UUID technicianCertificationId, String alertStage, String validityKey);
}

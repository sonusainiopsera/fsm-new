package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ReadinessRequirementRepository extends JpaRepository<ReadinessRequirementEntity, UUID> {

    List<ReadinessRequirementEntity> findAllByActiveTrueOrderByRequirementKindAscCertificationTypeCodeAscFieldNameAsc();
}

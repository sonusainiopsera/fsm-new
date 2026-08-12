package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
interface SlaRiskFlagRepository extends JpaRepository<SlaRiskFlagEntity, UUID> {

    List<SlaRiskFlagEntity> findByWorkOrderIdAndClearedAtIsNull(UUID workOrderId);

    Optional<SlaRiskFlagEntity> findByWorkOrderIdAndFlagTypeAndClearedAtIsNull(
            UUID workOrderId, String flagType);
}

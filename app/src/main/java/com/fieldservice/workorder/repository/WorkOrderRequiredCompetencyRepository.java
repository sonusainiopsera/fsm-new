package com.fieldservice.workorder.repository;

import com.fieldservice.workorder.domain.WorkOrderRequiredCompetency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkOrderRequiredCompetencyRepository extends JpaRepository<WorkOrderRequiredCompetency, UUID> {
    List<WorkOrderRequiredCompetency> findByWorkOrderId(UUID workOrderId);
}

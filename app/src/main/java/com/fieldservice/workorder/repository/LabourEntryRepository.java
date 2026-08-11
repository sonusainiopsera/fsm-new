package com.fieldservice.workorder.repository;

import com.fieldservice.workorder.domain.LabourEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LabourEntryRepository extends JpaRepository<LabourEntry, UUID> {
    boolean existsByWorkOrderId(UUID workOrderId);
}

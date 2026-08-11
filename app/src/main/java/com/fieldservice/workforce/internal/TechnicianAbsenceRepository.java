package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface TechnicianAbsenceRepository extends JpaRepository<TechnicianAbsenceEntity, UUID> {

    List<TechnicianAbsenceEntity> findByTechnicianId(UUID technicianId);
}

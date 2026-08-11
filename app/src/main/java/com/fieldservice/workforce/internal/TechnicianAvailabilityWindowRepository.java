package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface TechnicianAvailabilityWindowRepository
        extends JpaRepository<TechnicianAvailabilityWindowEntity, UUID> {

    List<TechnicianAvailabilityWindowEntity> findByTechnicianId(UUID technicianId);
}

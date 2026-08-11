package com.fieldservice.technician.repository;

import com.fieldservice.technician.domain.TechnicianCertification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TechnicianCertificationRepository extends JpaRepository<TechnicianCertification, UUID> {
    List<TechnicianCertification> findByTechnicianId(UUID technicianId);
}

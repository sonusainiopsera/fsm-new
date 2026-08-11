package com.fieldservice.technician.repository;

import com.fieldservice.technician.domain.Technician;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    Optional<Technician> findByUserId(UUID userId);
    List<Technician> findByActiveTrue();
    boolean existsByUserId(UUID userId);
}

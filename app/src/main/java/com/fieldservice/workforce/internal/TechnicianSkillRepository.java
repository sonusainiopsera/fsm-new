package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TechnicianSkillRepository extends JpaRepository<TechnicianSkillEntity, UUID> {

    List<TechnicianSkillEntity> findByTechnicianId(UUID technicianId);

    Optional<TechnicianSkillEntity> findByTechnicianIdAndSkillId(UUID technicianId, UUID skillId);

    boolean existsByTechnicianIdAndSkillId(UUID technicianId, UUID skillId);
}

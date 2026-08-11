package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    Optional<SkillEntity> findByCode(String code);

    List<SkillEntity> findByActiveTrue();

    boolean existsByCode(String code);
}

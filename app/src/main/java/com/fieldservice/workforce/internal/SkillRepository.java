package com.fieldservice.workforce.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

interface SkillRepository extends JpaRepository<SkillEntity, UUID>,
        JpaSpecificationExecutor<SkillEntity> {

    Optional<SkillEntity> findByCode(String code);
    boolean existsByCode(String code);
}

package com.fieldservice.workforce.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CertificationTypeRepository extends JpaRepository<CertificationTypeEntity, UUID> {

    Optional<CertificationTypeEntity> findByCode(String code);

    boolean existsByCode(String code);

    Page<CertificationTypeEntity> findAllByOrderByCodeAsc(Pageable pageable);
}

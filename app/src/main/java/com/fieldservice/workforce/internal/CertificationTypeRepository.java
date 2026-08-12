package com.fieldservice.workforce.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface CertificationTypeRepository extends JpaRepository<CertificationTypeEntity, UUID> {

    boolean existsByCode(String code);

    Optional<CertificationTypeEntity> findByCode(String code);

    List<CertificationTypeEntity> findByActiveTrue();

    Page<CertificationTypeEntity> findAll(Pageable pageable);

    /** Returns all active certification type entities whose code is in the given set. */
    List<CertificationTypeEntity> findByCodeInAndActiveTrue(Set<String> codes);
}

package com.fieldservice.privacy.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RetentionPolicyRepository extends JpaRepository<RetentionPolicyEntity, UUID> {

    Optional<RetentionPolicyEntity> findByDataCategory(String dataCategory);

    /** Returns only policies where both ratified and enabled are true — used by the sweep job. */
    @Query("SELECT r FROM RetentionPolicyEntity r WHERE r.ratified = true AND r.enabled = true")
    List<RetentionPolicyEntity> findAllRatifiedAndEnabled();

    @Override
    Page<RetentionPolicyEntity> findAll(Pageable pageable);
}

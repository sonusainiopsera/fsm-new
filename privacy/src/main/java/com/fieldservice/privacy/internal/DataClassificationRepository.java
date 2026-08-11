package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationTier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DataClassificationRepository extends JpaRepository<DataClassificationEntity, UUID> {

    Optional<DataClassificationEntity> findByEntityNameAndFieldNameIsNull(String entityName);

    Optional<DataClassificationEntity> findByEntityNameAndFieldName(String entityName, String fieldName);

    List<DataClassificationEntity> findByTier(ClassificationTier tier);

    Page<DataClassificationEntity> findByTier(ClassificationTier tier, Pageable pageable);
}

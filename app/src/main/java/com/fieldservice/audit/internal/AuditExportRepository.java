package com.fieldservice.audit.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface AuditExportRepository extends JpaRepository<AuditExportEntity, UUID> {

    Optional<AuditExportEntity> findByIdAndRequestedBy(UUID id, UUID requestedBy);
}

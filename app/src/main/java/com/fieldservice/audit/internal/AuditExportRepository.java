package com.fieldservice.audit.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Package-private JPA repository for {@link AuditExportEntity}. */
interface AuditExportRepository extends JpaRepository<AuditExportEntity, UUID> {}

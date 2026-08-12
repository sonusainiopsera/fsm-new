package com.fieldservice.privacy.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface DsarExportArtifactRepository extends JpaRepository<DsarExportArtifactEntity, UUID> {

    Optional<DsarExportArtifactEntity> findByDsarRequestId(UUID dsarRequestId);
}

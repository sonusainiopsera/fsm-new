package com.fieldservice.privacy.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Package-private JPA repository for {@link DsarExportArtifact} entities. */
interface DsarExportArtifactRepository extends JpaRepository<DsarExportArtifact, UUID> {

    Optional<DsarExportArtifact> findByDsarRequestId(UUID dsarRequestId);

    void deleteByDsarRequestId(UUID dsarRequestId);
}

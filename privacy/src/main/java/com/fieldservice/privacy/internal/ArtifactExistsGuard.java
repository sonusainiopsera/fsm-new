package com.fieldservice.privacy.internal;

import com.fieldservice.workorder.lifecycle.GuardResult;
import org.springframework.stereotype.Component;

/**
 * Guard that ensures an export artifact exists before a request can be marked FULFILLED.
 *
 * <p>The guard is referenced in {@link DsarTransitionTable} by
 * {@link DsarTransitionTable#GUARD_ARTIFACT_EXISTS}.
 */
@Component
class ArtifactExistsGuard implements DsarTransitionGuard {

    private final DsarExportArtifactRepository artifactRepository;

    ArtifactExistsGuard(DsarExportArtifactRepository artifactRepository) {
        this.artifactRepository = artifactRepository;
    }

    @Override
    public String guardId() {
        return DsarTransitionTable.GUARD_ARTIFACT_EXISTS;
    }

    @Override
    public GuardResult evaluate(DsarRequest request, DsarEvent event, DsarTransitionContext context) {
        boolean exists = artifactRepository.findByDsarRequestId(request.getId()).isPresent();
        if (!exists) {
            return new GuardResult.Refused(
                    "ARTIFACT_NOT_READY",
                    "Cannot mark request " + request.getId() + " as FULFILLED: no export artifact found. "
                    + "Ensure the export assembly job has completed successfully.");
        }
        return new GuardResult.Satisfied();
    }
}

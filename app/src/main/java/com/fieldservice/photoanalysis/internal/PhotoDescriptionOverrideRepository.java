package com.fieldservice.photoanalysis.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface PhotoDescriptionOverrideRepository extends JpaRepository<PhotoDescriptionOverride, UUID> {

    Optional<PhotoDescriptionOverride> findByWorkOrderPhotoIdAndAiInteractionId(
            UUID workOrderPhotoId, UUID aiInteractionId);
}

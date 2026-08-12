package com.fieldservice.photo.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkOrderPhotoRepository extends JpaRepository<WorkOrderPhoto, UUID> {

    List<WorkOrderPhoto> findByWorkOrderIdOrderByCreatedAtAsc(UUID workOrderId);

    Optional<WorkOrderPhoto> findByStorageKey(String storageKey);
}

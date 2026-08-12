package com.fieldservice.photo.repository;

import com.fieldservice.photo.domain.WorkOrderPhoto;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkOrderPhotoRepository extends JpaRepository<WorkOrderPhoto, UUID> {

    List<WorkOrderPhoto> findByWorkOrderIdOrderByCapturedAtAsc(UUID workOrderId);

    Optional<WorkOrderPhoto> findByStorageKey(String storageKey);

    Optional<WorkOrderPhoto> findByIdAndWorkOrderId(UUID id, UUID workOrderId);

    long countByWorkOrderId(UUID workOrderId);

    // ---- Retention queries --------------------------------------------------

    @Query("SELECT COUNT(p) FROM WorkOrderPhoto p WHERE p.retainUntil < :cutoff")
    long countByRetainUntilBefore(@Param("cutoff") LocalDate cutoff);

    @Query("SELECT MIN(p.retainUntil) FROM WorkOrderPhoto p WHERE p.retainUntil < :cutoff")
    Optional<LocalDate> findOldestRetainUntilBefore(@Param("cutoff") LocalDate cutoff);

    @Query("SELECT p.id FROM WorkOrderPhoto p WHERE p.retainUntil < :cutoff ORDER BY p.retainUntil ASC")
    List<UUID> findIdsWithRetainUntilBefore(@Param("cutoff") LocalDate cutoff, Pageable pageable);

    default List<UUID> findIdsWithRetainUntilBefore(LocalDate cutoff, int limit) {
        return findIdsWithRetainUntilBefore(cutoff, PageRequest.of(0, limit));
    }
}

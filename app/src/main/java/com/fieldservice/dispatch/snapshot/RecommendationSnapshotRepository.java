package com.fieldservice.dispatch.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Insert-and-read-only repository for recommendation snapshots.
 *
 * <p>No update or delete methods are exposed here or in the service layer.
 * An ArchUnit rule enforces that no code in this module calls save() on an existing
 * (already-persisted) snapshot entity.
 */
@Repository
public interface RecommendationSnapshotRepository extends JpaRepository<RecommendationSnapshot, UUID> {

    List<RecommendationSnapshot> findByWorkOrderIdOrderByGeneratedAtDesc(UUID workOrderId);
}

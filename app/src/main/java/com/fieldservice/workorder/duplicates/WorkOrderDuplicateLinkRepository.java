package com.fieldservice.workorder.duplicates;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkOrderDuplicateLinkRepository extends JpaRepository<WorkOrderDuplicateLink, UUID> {

    Optional<WorkOrderDuplicateLink> findBySourceWorkOrderId(UUID sourceWorkOrderId);

    /** Returns all links where the given work order is the target (surviving order). */
    List<WorkOrderDuplicateLink> findAllByTargetWorkOrderId(UUID targetWorkOrderId);

    /** Resolves the chain from source to ultimate surviving root (depth-limited in service). */
    @Query("SELECT l.targetWorkOrderId FROM WorkOrderDuplicateLink l WHERE l.sourceWorkOrderId = :sourceId")
    Optional<UUID> findTargetBySource(UUID sourceId);
}

package com.fieldservice.dispatch.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Insert-and-read-only repository for recommendation snapshot candidates.
 */
@Repository
public interface RecommendationSnapshotCandidateRepository extends JpaRepository<RecommendationSnapshotCandidate, UUID> {

    List<RecommendationSnapshotCandidate> findBySnapshotIdOrderByRankAsc(UUID snapshotId);
}

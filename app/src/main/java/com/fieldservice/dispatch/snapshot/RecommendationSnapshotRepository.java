package com.fieldservice.dispatch.snapshot;

import com.fieldservice.dispatch.scoring.FactorBreakdown;
import com.fieldservice.dispatch.scoring.ScoredCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Insert-only persistence for recommendation snapshots.
 *
 * <p>No update or delete operations are provided. Every snapshot is an immutable
 * audit record of what the dispatcher saw at generation time.
 */
@Repository
public class RecommendationSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(RecommendationSnapshotRepository.class);

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper               objectMapper;

    public RecommendationSnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.namedJdbc    = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
    }

    /**
     * Persists the snapshot header and all candidate rows in a single transaction.
     *
     * @param snapshotId              UUIDv7 for the new snapshot
     * @param workOrderId             the work order this snapshot belongs to
     * @param generatedAt             generation timestamp
     * @param generatedBy             actor UUID (may be null if principal is unavailable)
     * @param weightSetVersion        version string from {@code ScoringWeights}
     * @param travelEstimateDegraded  aggregate travel degradation flag
     * @param partsDataDegraded       aggregate parts degradation flag
     * @param candidatePoolSize       total eligible candidate count before pagination
     * @param truncated               true when pool was capped at the server maximum
     * @param rankedCandidates        full ranked candidate list (all ranks, not just the page)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void persist(
            UUID snapshotId,
            UUID workOrderId,
            Instant generatedAt,
            UUID generatedBy,
            String weightSetVersion,
            boolean travelEstimateDegraded,
            boolean partsDataDegraded,
            int candidatePoolSize,
            boolean truncated,
            List<ScoredCandidate> rankedCandidates) {

        namedJdbc.update("""
                INSERT INTO recommendation_snapshot
                    (id, work_order_id, generated_at, generated_by, weight_set_version,
                     travel_estimate_degraded, parts_data_degraded, candidate_pool_size, truncated)
                VALUES
                    (:id, :workOrderId, :generatedAt, :generatedBy, :weightSetVersion,
                     :travelEstimateDegraded, :partsDataDegraded, :candidatePoolSize, :truncated)
                """,
                new MapSqlParameterSource()
                        .addValue("id",                    snapshotId)
                        .addValue("workOrderId",           workOrderId)
                        .addValue("generatedAt",           Timestamp.from(generatedAt))
                        .addValue("generatedBy",           generatedBy)
                        .addValue("weightSetVersion",      weightSetVersion)
                        .addValue("travelEstimateDegraded", travelEstimateDegraded)
                        .addValue("partsDataDegraded",     partsDataDegraded)
                        .addValue("candidatePoolSize",     candidatePoolSize)
                        .addValue("truncated",             truncated));

        insertCandidates(snapshotId, rankedCandidates);
        log.info("dispatch.recommendation.snapshot persisted snapshotId={} workOrderId={} candidates={}",
                snapshotId, workOrderId, rankedCandidates.size());
    }

    /**
     * Returns the snapshot header for a given id, or an empty result if not found.
     * Does not load candidate rows — use {@link #findCandidatesBySnapshotId} for those.
     */
    public List<Map<String, Object>> findById(UUID snapshotId) {
        return namedJdbc.queryForList(
                "SELECT * FROM recommendation_snapshot WHERE id = :id",
                Map.of("id", snapshotId));
    }

    /**
     * Returns all candidate rows for a snapshot, ordered by rank ascending.
     */
    public List<Map<String, Object>> findCandidatesBySnapshotId(UUID snapshotId) {
        return namedJdbc.queryForList(
                "SELECT * FROM recommendation_snapshot_candidate WHERE snapshot_id = :id ORDER BY rank",
                Map.of("id", snapshotId));
    }

    // ─── internals ────────────────────────────────────────────────────────────────────────

    private void insertCandidates(UUID snapshotId, List<ScoredCandidate> candidates) {
        List<MapSqlParameterSource> batchParams = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            ScoredCandidate c = candidates.get(i);
            batchParams.add(new MapSqlParameterSource()
                    .addValue("id",           UUID.randomUUID())
                    .addValue("snapshotId",   snapshotId)
                    .addValue("technicianId", c.technicianId())
                    .addValue("rank",         i + 1)
                    .addValue("score",        c.compositeScore())
                    .addValue("breakdown",    toJsonb(c.breakdown())));
        }
        namedJdbc.batchUpdate("""
                INSERT INTO recommendation_snapshot_candidate
                    (id, snapshot_id, technician_id, rank, score, factor_breakdown)
                VALUES
                    (:id, :snapshotId, :technicianId, :rank, :score, :breakdown::jsonb)
                """,
                batchParams.toArray(MapSqlParameterSource[]::new));
    }

    private String toJsonb(List<FactorBreakdown> breakdown) {
        try {
            return objectMapper.writeValueAsString(breakdown);
        } catch (JsonProcessingException e) {
            log.warn("dispatch.recommendation.snapshot factor breakdown serialisation failed", e);
            return "[]";
        }
    }
}

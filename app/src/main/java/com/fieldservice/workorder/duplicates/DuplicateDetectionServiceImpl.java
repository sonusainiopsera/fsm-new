package com.fieldservice.workorder.duplicates;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Index-assisted duplicate detection using PostgreSQL's GIN-indexed fault_signature column.
 *
 * <p>Detection query is bounded by:
 * <ul>
 *   <li>customer_id equality (B-tree index)</li>
 *   <li>creation window (configurable hours, B-tree index)</li>
 *   <li>open-state filter (B-tree index on state)</li>
 *   <li>GIN {@code &&} overlap filter on fault_signature (when tokens are non-empty)</li>
 *   <li>LIMIT {@value} candidates</li>
 * </ul>
 *
 * <p>The query does NOT use string concatenation for parameters; all values
 * are bound via PreparedStatement to satisfy A05 (injection prevention).
 */
@Service
@Transactional(readOnly = true)
@EnableConfigurationProperties(DuplicateDetectionProperties.class)
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionServiceImpl.class);

    private static final String OPEN_STATES_LITERAL =
            "'NEW','ASSIGNED','EN_ROUTE','IN_PROGRESS','ON_HOLD'";

    private final JdbcTemplate jdbcTemplate;
    private final DuplicateDetectionProperties props;

    public DuplicateDetectionServiceImpl(JdbcTemplate jdbcTemplate,
                                          DuplicateDetectionProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    @Override
    public List<DuplicateCandidate> detect(WorkOrder workOrder) {
        String[] tokens = workOrder.getFaultSignature();
        UUID customerId = workOrder.getCustomerId();
        UUID assetId    = workOrder.getAssetId();
        UUID siteId     = workOrder.getSiteId();
        UUID excludeId  = workOrder.getId();
        Instant windowStart = Instant.now().minus(Duration.ofHours(props.getWindowHours()));

        boolean hasTokens = tokens != null && tokens.length >= props.getMinSignatureOverlap();

        // Build the SQL. The table name and state literals are hard-coded (not user-supplied).
        String sql = buildQuery(hasTokens);

        try {
            return jdbcTemplate.query(conn -> {
                var ps = conn.prepareStatement(sql);
                int idx = 1;

                // SELECT projections
                ps.setObject(idx++, assetId);  // asset_match
                ps.setObject(idx++, siteId);   // site_match

                // WHERE clause
                ps.setObject(idx++, customerId);
                if (excludeId != null) {
                    ps.setObject(idx++, excludeId);
                }
                ps.setTimestamp(idx++, Timestamp.from(windowStart));

                // OR filter: asset / site / signature
                ps.setObject(idx++, assetId);
                ps.setObject(idx++, siteId);
                if (hasTokens) {
                    Array arr = conn.createArrayOf("text", tokens);
                    ps.setArray(idx++, arr);  // fault_signature &&
                }

                // ORDER BY overlap_count (repeated binding)
                if (hasTokens) {
                    Array arr2 = conn.createArrayOf("text", tokens);
                    ps.setArray(idx++, arr2);
                }
                ps.setObject(idx++, assetId);  // asset_match ORDER
                ps.setObject(idx++, siteId);   // site_match ORDER

                ps.setInt(idx, props.getMaxCandidates());
                return ps;
            }, (rs, rowNum) -> mapCandidate(rs, tokens, assetId, siteId));
        } catch (Exception ex) {
            log.warn("duplicate.detection.failed: workOrderId={}, error={}",
                    workOrder.getId(), ex.getMessage());
            return List.of();
        }
    }

    private String buildQuery(boolean hasTokens) {
        // overlap_count sub-expression: counts tokens shared with this work order
        String overlapExpr = hasTokens
                ? "(SELECT count(*) FROM unnest(wo.fault_signature) AS t WHERE t = ANY(?))"
                : "0";

        String excludeClause = "AND wo.id != ?";

        // OR filter for candidate inclusion
        String signatureFilter = hasTokens
                ? "OR wo.fault_signature && ?"
                : "";

        return "SELECT " +
               "  wo.id, " +
               "  wo.reference, " +
               "  wo.state, " +
               "  wo.created_at, " +
               "  EXTRACT(EPOCH FROM (now() - wo.created_at)) / 3600.0 AS age_hours, " +
               "  (wo.asset_id = ?) AS asset_match, " +
               "  (wo.site_id = ?)  AS site_match, " +
               "  " + overlapExpr + " AS overlap_count " +
               "FROM work_order wo " +
               "WHERE wo.customer_id = ? " +
               "  " + excludeClause + " " +
               "  AND wo.state IN (" + OPEN_STATES_LITERAL + ") " +
               "  AND wo.created_at >= ? " +
               "  AND ( " +
               "      wo.asset_id = ? " +
               "      OR wo.site_id = ? " +
               "      " + signatureFilter +
               "  ) " +
               "ORDER BY " +
               "  " + overlapExpr + " DESC, " +
               "  (wo.asset_id = ?) DESC, " +
               "  (wo.site_id = ?)  DESC, " +
               "  wo.created_at ASC " +
               "LIMIT ?";
    }

    private DuplicateCandidate mapCandidate(ResultSet rs, String[] refTokens,
                                             UUID assetId, UUID siteId) throws SQLException {
        UUID id           = (UUID) rs.getObject("id");
        String reference  = rs.getString("reference");
        WorkOrderState st = WorkOrderState.valueOf(rs.getString("state"));
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        double ageHours   = rs.getDouble("age_hours");
        boolean assetMatch = rs.getBoolean("asset_match");
        boolean siteMatch  = rs.getBoolean("site_match");
        long overlapCount  = rs.getLong("overlap_count");

        List<DuplicateCandidate.BasisTag> basis = new ArrayList<>();
        if (assetMatch) basis.add(DuplicateCandidate.BasisTag.ASSET_MATCH);
        if (siteMatch)  basis.add(DuplicateCandidate.BasisTag.SITE_MATCH);
        if (overlapCount >= props.getMinSignatureOverlap()) {
            basis.add(DuplicateCandidate.BasisTag.SIGNATURE_OVERLAP);
        }

        return new DuplicateCandidate(
                id, reference, st, createdAt,
                ageHours, List.copyOf(basis), (int) overlapCount);
    }
}

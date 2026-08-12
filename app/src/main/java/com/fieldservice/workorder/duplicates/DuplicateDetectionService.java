package com.fieldservice.workorder.duplicates;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Advisory duplicate detection. Never blocks creation — any failure degrades to empty list.
 *
 * <p>Detection rules (deterministic, no ML):
 * <ol>
 *   <li>Same customer, open state, within configured window hours</li>
 *   <li>Same asset OR same site narrows the candidate set</li>
 *   <li>Fault-signature Jaccard >= configured threshold adds SIGNATURE_OVERLAP basis</li>
 *   <li>Candidates ranked: ASSET_MATCH first, then SITE_MATCH, then overlap score desc</li>
 *   <li>Truncated to at most 5 results</li>
 * </ol>
 *
 * <p>Row-scope: CUSTOMER principals are filtered to their own customer account ids.
 * DISPATCHER/ADMIN/MANAGER see all candidates for the same customer as the new work order.
 */
@Service
public class DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionService.class);

    private static final int MAX_CANDIDATES = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final DuplicateProperties        props;

    public DuplicateDetectionService(NamedParameterJdbcTemplate jdbc, DuplicateProperties props) {
        this.jdbc  = jdbc;
        this.props = props;
    }

    /**
     * Finds up to five duplicate candidates for {@code newWorkOrder} within the caller's scope.
     * Never throws — returns empty list on any detection failure.
     *
     * @param newWorkOrder the just-committed work order to check against
     * @param customerId   the customer this work order belongs to (from site.customer_id)
     * @param scope        the authenticated caller's scope (for row filtering)
     * @return advisory list of up to five candidates, ordered by match quality
     */
    @Transactional(readOnly = true)
    public List<DuplicateCandidate> detect(WorkOrder newWorkOrder, UUID customerId, AccessScope scope) {
        try {
            return doDetect(newWorkOrder, customerId, scope);
        } catch (Exception ex) {
            log.warn("duplicate_detection_failed work_order_id={} error={}", newWorkOrder.getId(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * On-demand detection for an existing work order. Used by GET /{id}/duplicate-candidates.
     * Applies the same rules as intake detection; row-scope enforced.
     */
    @Transactional(readOnly = true)
    public List<DuplicateCandidate> detectForExisting(UUID workOrderId, UUID siteId, UUID assetId,
                                                       UUID customerId, String faultSignatureTokens,
                                                       AccessScope scope) {
        try {
            return fetchCandidates(workOrderId, siteId, assetId, customerId,
                    faultSignatureTokens, scope);
        } catch (Exception ex) {
            log.warn("duplicate_detection_on_demand_failed work_order_id={} error={}", workOrderId, ex.getMessage());
            return List.of();
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private List<DuplicateCandidate> doDetect(WorkOrder newWorkOrder, UUID customerId, AccessScope scope) {
        UUID   siteId    = newWorkOrder.getSite().getId();
        UUID   assetId   = newWorkOrder.getAssetId();
        String signature = newWorkOrder.getFaultSignatureTokens();
        return fetchCandidates(newWorkOrder.getId(), siteId, assetId, customerId, signature, scope);
    }

    private List<DuplicateCandidate> fetchCandidates(UUID excludeId, UUID siteId, UUID assetId,
                                                      UUID customerId, String signature,
                                                      AccessScope scope) {
        Instant windowStart = Instant.now().minus(props.windowHours(), ChronoUnit.HOURS);

        // Open states for the IN clause
        Set<String> openStates = Set.of("NEW", "ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD");

        // Row-scope: CUSTOMER principals restricted to their customer account ids.
        // DISPATCHER/ADMIN/MANAGER see all open work orders for this customer.
        boolean isCustScope = scope.isCustomer() && !scope.customerAccountIds().isEmpty();
        if (isCustScope && !scope.customerAccountIds().contains(customerId)) {
            return List.of();
        }

        String sql =
            "SELECT wo.id, wo.reference, wo.state, wo.created_at, wo.asset_id, " +
            "       wo.fault_signature_tokens, s.id AS site_id " +
            "FROM work_order wo " +
            "JOIN site s ON wo.site_id = s.id " +
            "WHERE s.customer_id = :customerId " +
            "  AND wo.id <> :excludeId " +
            "  AND wo.state IN (:openStates) " +
            "  AND wo.created_at >= :windowStart " +
            "  AND (wo.site_id = :siteId OR wo.asset_id = :assetId) " +
            "ORDER BY wo.created_at DESC";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerId",   customerId)
                .addValue("excludeId",    excludeId)
                .addValue("openStates",   openStates)
                .addValue("windowStart",  java.sql.Timestamp.from(windowStart))
                .addValue("siteId",       siteId)
                .addValue("assetId",      assetId);

        List<RawRow> rows = jdbc.query(sql, params, (rs, n) -> new RawRow(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                WorkOrderStatus.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("asset_id", UUID.class),
                rs.getString("fault_signature_tokens"),
                rs.getObject("site_id", UUID.class)
        ));

        List<DuplicateCandidate> candidates = new ArrayList<>();
        for (RawRow row : rows) {
            List<String> basis  = buildBasis(row, siteId, assetId, signature);
            double overlap = FaultSignatureNormalizer.jaccard(signature, row.faultSignatureTokens());
            if (basis.isEmpty() && overlap < props.minSignatureOverlap()) continue;
            long ageHours = ChronoUnit.HOURS.between(row.createdAt(), Instant.now());
            candidates.add(new DuplicateCandidate(
                    row.id(), row.reference(), row.state(), row.createdAt(),
                    ageHours, basis, overlap));
        }

        // Sort: ASSET_MATCH first, then SITE_MATCH, then overlap score desc
        candidates.sort((a, b) -> {
            int aScore = (a.basis().contains(DuplicateCandidate.ASSET_MATCH) ? 4 : 0)
                       + (a.basis().contains(DuplicateCandidate.SITE_MATCH)  ? 2 : 0)
                       + (a.basis().contains(DuplicateCandidate.SIGNATURE_OVERLAP) ? 1 : 0);
            int bScore = (b.basis().contains(DuplicateCandidate.ASSET_MATCH) ? 4 : 0)
                       + (b.basis().contains(DuplicateCandidate.SITE_MATCH)  ? 2 : 0)
                       + (b.basis().contains(DuplicateCandidate.SIGNATURE_OVERLAP) ? 1 : 0);
            if (bScore != aScore) return Integer.compare(bScore, aScore);
            return Double.compare(b.signatureOverlapScore(), a.signatureOverlapScore());
        });

        return candidates.size() > MAX_CANDIDATES
                ? candidates.subList(0, MAX_CANDIDATES)
                : candidates;
    }

    private List<String> buildBasis(RawRow row, UUID siteId, UUID assetId, String newSignature) {
        List<String> basis = new ArrayList<>();
        if (assetId != null && assetId.equals(row.assetId())) {
            basis.add(DuplicateCandidate.ASSET_MATCH);
        }
        if (siteId.equals(row.siteId())) {
            basis.add(DuplicateCandidate.SITE_MATCH);
        }
        double overlap = FaultSignatureNormalizer.jaccard(newSignature, row.faultSignatureTokens());
        if (overlap >= props.minSignatureOverlap()) {
            basis.add(DuplicateCandidate.SIGNATURE_OVERLAP);
        }
        return basis;
    }

    private record RawRow(UUID id, String reference, WorkOrderStatus state, Instant createdAt,
                          UUID assetId, String faultSignatureTokens, UUID siteId) {}
}

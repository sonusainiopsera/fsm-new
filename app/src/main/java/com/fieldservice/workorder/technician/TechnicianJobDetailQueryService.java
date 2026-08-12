package com.fieldservice.workorder.technician;

import com.fieldservice.workorder.holds.HoldReasonResponse;
import com.fieldservice.workorder.holds.HoldReasonService;
import com.fieldservice.workorder.lifecycle.AllowedTransitionResolver;
import com.fieldservice.workorder.lifecycle.WorkOrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC read-only query service for the technician job-detail endpoint.
 *
 * <p>Uses a single SQL query joining work_order, site, asset and
 * work_order_required_competency to build the detail projection. All row-scope
 * enforcement is via the {@code assigned_technician_id} predicate — no row is
 * fetched unless it belongs to the caller.
 */
@Service
public class TechnicianJobDetailQueryService {

    private static final Logger log = LoggerFactory.getLogger(TechnicianJobDetailQueryService.class);

    private final JdbcTemplate             jdbc;
    private final AllowedTransitionResolver resolver;
    private final HoldReasonService        holdReasonService;

    public TechnicianJobDetailQueryService(JdbcTemplate jdbc,
                                           AllowedTransitionResolver resolver,
                                           HoldReasonService holdReasonService) {
        this.jdbc             = jdbc;
        this.resolver         = resolver;
        this.holdReasonService = holdReasonService;
    }

    /**
     * Returns the full job detail for {@code workOrderId}, scoped to {@code technicianId}.
     *
     * @param workOrderId  the work order to fetch
     * @param technicianId the authenticated technician's UUID (from JWT — never from request)
     * @return detail, or empty if not found or not assigned to this technician
     */
    public Optional<TechnicianJobDetail> findDetail(UUID workOrderId, UUID technicianId) {
        String sql =
            "SELECT wo.id, wo.reference, wo.priority, wo.state, " +
            "  wo.scheduled_window_start, wo.scheduled_window_end, " +
            "  s.name AS site_name, " +
            "  TRIM(CONCAT_WS(', ', s.address_line1, s.city, s.postcode)) AS site_address, " +
            "  s.access_notes AS site_access_notes, " +
            "  s.primary_contact_name AS contact_name, " +
            "  s.contact_phone, " +
            "  a.id AS asset_id, a.asset_tag, a.model AS asset_description, " +
            "  wo.description AS fault_summary, wo.fault_code, wo.fault_category, " +
            "  wo.response_deadline, wo.resolution_deadline, wo.at_risk AS sla_at_risk, " +
            "  wo.version " +
            "FROM work_order wo " +
            "JOIN site s ON s.id = wo.site_id " +
            "LEFT JOIN asset a ON a.id = wo.asset_id " +
            "WHERE wo.id = ? AND wo.assigned_technician_id = ?";

        List<TechnicianJobDetail> results = jdbc.query(sql,
            new Object[]{workOrderId, technicianId},
            (rs, rowNum) -> {
                String rawState = rs.getString("state");
                WorkOrderState state;
                try {
                    state = WorkOrderState.valueOf(rawState);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown work order state '{}' for WO {}", rawState, workOrderId);
                    state = WorkOrderState.CLOSED;
                }

                List<String> allowed = resolver.allowedEvents(state, "TECHNICIAN");
                List<HoldReasonResponse> holdReasons = allowed.contains("HOLD")
                        ? holdReasonService.activeReasons()
                        : List.of();

                List<String> certifications = loadCertifications(workOrderId);
                List<TechnicianJobDetail.ExpectedPart> parts = loadExpectedParts(workOrderId);

                return new TechnicianJobDetail(
                        (UUID) rs.getObject("id"),
                        rs.getString("reference"),
                        rs.getString("priority"),
                        rawState,
                        rs.getTimestamp("scheduled_window_start") != null
                                ? rs.getTimestamp("scheduled_window_start").toInstant() : null,
                        rs.getTimestamp("scheduled_window_end") != null
                                ? rs.getTimestamp("scheduled_window_end").toInstant() : null,
                        rs.getString("site_name"),
                        rs.getString("site_address"),
                        rs.getString("site_access_notes"),
                        rs.getString("contact_name"),
                        ContactMasker.maskPhone(rs.getString("contact_phone")),
                        rs.getObject("asset_id", UUID.class),
                        rs.getString("asset_tag"),
                        rs.getString("asset_description"),
                        rs.getString("fault_summary"),
                        rs.getString("fault_code"),
                        rs.getString("fault_category"),
                        certifications,
                        parts,
                        rs.getTimestamp("response_deadline") != null
                                ? rs.getTimestamp("response_deadline").toInstant() : null,
                        rs.getTimestamp("resolution_deadline") != null
                                ? rs.getTimestamp("resolution_deadline").toInstant() : null,
                        rs.getBoolean("sla_at_risk"),
                        rs.getInt("version"),
                        allowed,
                        holdReasons
                );
            });

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private List<String> loadCertifications(UUID workOrderId) {
        return jdbc.queryForList(
            "SELECT certification_code FROM work_order_required_competency WHERE work_order_id = ?",
            String.class, workOrderId);
    }

    private List<TechnicianJobDetail.ExpectedPart> loadExpectedParts(UUID workOrderId) {
        return jdbc.query(
            "SELECT p.part_number, p.name, rp.quantity_required " +
            "FROM work_order_required_part rp " +
            "JOIN part p ON p.id = rp.part_id " +
            "WHERE rp.work_order_id = ?",
            new Object[]{workOrderId},
            (rs, rowNum) -> new TechnicianJobDetail.ExpectedPart(
                    rs.getString("part_number"),
                    rs.getString("name"),
                    rs.getInt("quantity_required")));
    }
}

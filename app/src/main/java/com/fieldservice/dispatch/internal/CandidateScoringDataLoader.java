package com.fieldservice.dispatch.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads the minimal technician data needed to assemble {@link com.fieldservice.dispatch.scoring.ScoringContext}
 * objects: home-base coordinates, certification codes, and booked hours.
 *
 * <p>Two JDBC round-trips regardless of candidate count:
 * <ol>
 *   <li>Technician rows with their home-site coordinates and active certification codes.</li>
 *   <li>Current booked hours per technician (active ASSIGNED/EN_ROUTE/IN_PROGRESS work orders).</li>
 * </ol>
 */
@Component
class CandidateScoringDataLoader {

    private static final String TECHNICIAN_SQL = """
            SELECT
                t.id::text             AS tid,
                t.display_name         AS display_name,
                s.latitude             AS home_lat,
                s.longitude            AS home_lon,
                ct.code                AS cert_code,
                sl.id::text            AS vehicle_stock_location_id
            FROM technician t
            LEFT JOIN site s
                ON s.id = t.home_base_site_id
            LEFT JOIN technician_certification tc
                ON tc.technician_id = t.id AND tc.active = true
            LEFT JOIN certification_type ct
                ON ct.id = tc.certification_type_id
            LEFT JOIN stock_location sl
                ON sl.technician_id = t.id
                AND sl.location_type = 'VEHICLE'
                AND sl.is_active = true
            WHERE t.id = ANY(CAST(:ids AS uuid[]))
            ORDER BY t.id
            """;

    private static final String BOOKED_HOURS_SQL = """
            SELECT
                a.technician_id::text   AS tid,
                COALESCE(SUM(
                    EXTRACT(EPOCH FROM (a.scheduled_end - a.scheduled_start)) / 3600.0
                ), 0.0)                AS booked_hours
            FROM assignment a
            WHERE a.technician_id = ANY(CAST(:ids AS uuid[]))
              AND a.state IN ('SCHEDULED', 'ACTIVE')
            GROUP BY a.technician_id
            """;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    List<TechnicianScoringData> load(Collection<UUID> technicianIds) {
        if (technicianIds.isEmpty()) {
            return List.of();
        }
        String idsLiteral = toPgArrayLiteral(technicianIds);

        List<Object[]> techRows = em.createNativeQuery(TECHNICIAN_SQL)
                .setParameter("ids", idsLiteral)
                .getResultList();

        LinkedHashMap<UUID, TechnicianScoringData.Builder> builders = new LinkedHashMap<>();
        for (Object[] row : techRows) {
            UUID tid = UUID.fromString((String) row[0]);
            builders.computeIfAbsent(tid, id ->
                new TechnicianScoringData.Builder(id, (String) row[1],
                        toDouble(row[2]), toDouble(row[3])));
            if (row[4] != null) {
                builders.get(tid).addCert((String) row[4]);
            }
            if (row[5] != null) {
                builders.get(tid).vehicleStockLocationId(UUID.fromString((String) row[5]));
            }
        }

        List<Object[]> bookedRows = em.createNativeQuery(BOOKED_HOURS_SQL)
                .setParameter("ids", idsLiteral)
                .getResultList();

        for (Object[] row : bookedRows) {
            UUID tid = UUID.fromString((String) row[0]);
            TechnicianScoringData.Builder b = builders.get(tid);
            if (b != null) {
                b.bookedHours(((Number) row[1]).doubleValue());
            }
        }

        return builders.values().stream()
                .map(TechnicianScoringData.Builder::build)
                .toList();
    }

    private static Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd.doubleValue();
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }

    private static String toPgArrayLiteral(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (UUID id : ids) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}

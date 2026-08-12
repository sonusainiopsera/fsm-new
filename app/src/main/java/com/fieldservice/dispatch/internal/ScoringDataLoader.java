package com.fieldservice.dispatch.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads technician data required for scoring from the database.
 *
 * <p>Issues five SQL statements for the entire eligible set (no N+1):
 * <ol>
 *   <li>Home base coordinates (technician + site join)</li>
 *   <li>Active certification codes</li>
 *   <li>Prior experience count for the fault category</li>
 *   <li>Today's booked hours (scheduled but not yet completed work orders)</li>
 *   <li>Vehicle stock location ID (for parts availability lookup)</li>
 * </ol>
 */
@Component
public class ScoringDataLoader {

    private static final Logger log = LoggerFactory.getLogger(ScoringDataLoader.class);

    private final NamedParameterJdbcTemplate namedJdbc;

    public ScoringDataLoader(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * Loads all scoring inputs for the given eligible technician set.
     *
     * @param eligibleIds  technician IDs that passed eligibility filtering
     * @param faultCategory work order fault category for experience matching (may be null)
     * @param serviceDate  reference date for booked-hours calculation (UTC)
     * @return per-technician scoring inputs, keyed by technician id
     */
    @Transactional(readOnly = true)
    public Map<UUID, TechnicianScoringInput> load(List<UUID> eligibleIds,
                                                   String faultCategory,
                                                   LocalDate serviceDate) {
        if (eligibleIds.isEmpty()) {
            return Map.of();
        }

        List<String> idStrings = eligibleIds.stream().map(UUID::toString).toList();
        MapSqlParameterSource idsParam = new MapSqlParameterSource("ids", idStrings);

        // ── Statement 1: home-base coordinates ───────────────────────────────
        Map<UUID, double[]> coordsByTech = new HashMap<>();
        namedJdbc.query("""
                SELECT t.id::text, s.latitude, s.longitude
                FROM technician t
                LEFT JOIN site s ON s.id = t.home_base_site_id
                WHERE t.id::text IN (:ids)
                """,
                idsParam,
                rs -> {
                    UUID   id  = UUID.fromString(rs.getString(1));
                    Object lat = rs.getObject(2);
                    Object lon = rs.getObject(3);
                    double dLat = lat != null ? ((Number) lat).doubleValue() : Double.NaN;
                    double dLon = lon != null ? ((Number) lon).doubleValue() : Double.NaN;
                    coordsByTech.put(id, new double[]{dLat, dLon});
                });

        // ── Statement 2: active certification codes ───────────────────────────
        Map<UUID, List<String>> certsByTech = new HashMap<>();
        namedJdbc.query("""
                SELECT tc.technician_id::text, ct.code
                FROM technician_certification tc
                JOIN certification_type ct ON ct.id = tc.certification_type_id
                WHERE tc.technician_id::text IN (:ids)
                  AND tc.active = true
                  AND ct.active = true
                """,
                idsParam,
                rs -> {
                    UUID   id   = UUID.fromString(rs.getString(1));
                    String code = rs.getString(2);
                    certsByTech.computeIfAbsent(id, k -> new ArrayList<>()).add(code);
                });

        // ── Statement 3: prior job-type experience ────────────────────────────
        Map<UUID, Integer> experienceByTech = new HashMap<>();
        if (faultCategory != null && !faultCategory.isBlank()) {
            MapSqlParameterSource expParams = new MapSqlParameterSource()
                    .addValue("ids",          idStrings)
                    .addValue("faultCategory", faultCategory);
            namedJdbc.query("""
                    SELECT assigned_technician_id::text, COUNT(*) AS exp
                    FROM work_order
                    WHERE assigned_technician_id::text IN (:ids)
                      AND fault_category = :faultCategory
                      AND state IN ('COMPLETED', 'CLOSED')
                    GROUP BY assigned_technician_id
                    """,
                    expParams,
                    rs -> {
                        UUID id  = UUID.fromString(rs.getString(1));
                        int  exp = rs.getInt(2);
                        experienceByTech.put(id, exp);
                    });
        }

        // ── Statement 4: today's booked hours ────────────────────────────────
        Map<UUID, Double> bookedHoursByTech = new HashMap<>();
        Instant dayStart = serviceDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd   = serviceDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        MapSqlParameterSource bookedParams = new MapSqlParameterSource()
                .addValue("ids",      idStrings)
                .addValue("dayStart", Timestamp.from(dayStart))
                .addValue("dayEnd",   Timestamp.from(dayEnd));
        namedJdbc.query("""
                SELECT wo.assigned_technician_id::text,
                       COALESCE(SUM(
                           EXTRACT(EPOCH FROM (wo.scheduled_window_end - wo.scheduled_window_start)) / 3600
                       ), 0) AS booked_hours
                FROM work_order wo
                WHERE wo.assigned_technician_id::text IN (:ids)
                  AND wo.state IN ('ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD')
                  AND wo.scheduled_window_start >= :dayStart
                  AND wo.scheduled_window_start <  :dayEnd
                GROUP BY wo.assigned_technician_id
                """,
                bookedParams,
                rs -> {
                    UUID   id    = UUID.fromString(rs.getString(1));
                    double hours = rs.getDouble(2);
                    bookedHoursByTech.put(id, hours);
                });

        // ── Statement 5: vehicle stock location IDs ───────────────────────────
        Map<UUID, UUID> vehicleLocationByTech = new HashMap<>();
        namedJdbc.query("""
                SELECT sl.technician_id::text, sl.id::text
                FROM stock_location sl
                WHERE sl.technician_id::text IN (:ids)
                  AND sl.location_type = 'VEHICLE'
                """,
                idsParam,
                rs -> {
                    UUID techId = UUID.fromString(rs.getString(1));
                    UUID locId  = UUID.fromString(rs.getString(2));
                    vehicleLocationByTech.put(techId, locId);
                });

        // ── Assemble ──────────────────────────────────────────────────────────
        Map<UUID, TechnicianScoringInput> result = new HashMap<>(eligibleIds.size());
        for (UUID id : eligibleIds) {
            double[] coords = coordsByTech.getOrDefault(id, new double[]{Double.NaN, Double.NaN});
            result.put(id, new TechnicianScoringInput(
                    id,
                    Double.isNaN(coords[0]) ? null : coords[0],
                    Double.isNaN(coords[1]) ? null : coords[1],
                    certsByTech.getOrDefault(id, List.of()),
                    experienceByTech.getOrDefault(id, 0),
                    bookedHoursByTech.getOrDefault(id, 0.0),
                    vehicleLocationByTech.get(id)
            ));
        }

        log.debug("dispatch.scoring.data loaded technicianCount={} faultCategory={}",
                result.size(), faultCategory);
        return result;
    }

    /**
     * All scoring-relevant data for one technician candidate.
     *
     * @param technicianId       candidate identifier
     * @param homeLatitude       home-base site latitude; null when site has no geocoding
     * @param homeLongitude      home-base site longitude; null when site has no geocoding
     * @param certificationCodes active certification codes the technician holds
     * @param priorJobExperience count of prior completed work orders matching the fault category
     * @param bookedHours        hours already booked for the service date
     * @param vehicleLocationId  vehicle stock location ID; null when technician has no vehicle
     */
    public record TechnicianScoringInput(
            UUID         technicianId,
            Double       homeLatitude,
            Double       homeLongitude,
            List<String> certificationCodes,
            int          priorJobExperience,
            double       bookedHours,
            UUID         vehicleLocationId) {
    }
}

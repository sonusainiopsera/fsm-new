package com.fieldservice.dispatch.eligibility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatch-owned read repository for loading technician candidates.
 *
 * <p>Issues exactly 4 SQL statements per call regardless of candidate pool size
 * (no N+1): one for technicians, one for certifications, one for shift windows,
 * and one for overlapping absences.  All queries use named parameters only —
 * no string concatenation.
 */
@Repository
class CandidateReadRepository {

    /** Server-enforced upper bound on candidate pool size per request. */
    static final int MAX_CANDIDATE_SIZE = 200;

    private final JdbcTemplate                  jdbc;
    private final NamedParameterJdbcTemplate    namedJdbc;

    CandidateReadRepository(JdbcTemplate jdbc) {
        this.jdbc      = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * Loads up to {@link #MAX_CANDIDATE_SIZE} technician candidates enriched with
     * certifications, shift windows, and absences that overlap the given service window.
     *
     * @param windowStart service window start — used to filter overlapping absences
     * @param windowEnd   service window end — used to filter overlapping absences
     * @return assembled candidates ready for EligibilityFilter
     */
    List<TechnicianCandidate> loadCandidates(Instant windowStart, Instant windowEnd) {

        // ── Statement 1: all technicians with home-base site coordinates ──────
        List<Object[]> techRows = jdbc.query("""
                SELECT t.id::text, t.active, COALESCE(t.timezone, 'UTC'),
                       s.latitude, s.longitude
                FROM technician t
                LEFT JOIN site s ON s.id = t.home_base_site_id
                ORDER BY t.id
                LIMIT ?
                """,
                (rs, i) -> new Object[]{
                        rs.getString(1), rs.getBoolean(2), rs.getString(3),
                        rs.getObject(4), rs.getObject(5)
                },
                MAX_CANDIDATE_SIZE);

        if (techRows.isEmpty()) {
            return List.of();
        }

        List<String> idStrings = techRows.stream()
                .map(r -> (String) r[0]).toList();

        MapSqlParameterSource idsParam = new MapSqlParameterSource("ids", idStrings);

        // ── Statement 2: certifications for those technicians ─────────────────
        Map<UUID, List<CertificationRecord>> certsByTech = new HashMap<>();
        namedJdbc.query("""
                SELECT tc.technician_id::text, ct.code, tc.expires_on
                FROM technician_certification tc
                JOIN certification_type ct ON ct.id = tc.certification_type_id
                WHERE tc.technician_id::text IN (:ids)
                  AND tc.active = true
                  AND ct.active = true
                """,
                idsParam,
                (rs) -> {
                    UUID      techId = UUID.fromString(rs.getString(1));
                    String    code   = rs.getString(2);
                    LocalDate exp    = rs.getDate(3) != null ? rs.getDate(3).toLocalDate() : null;
                    certsByTech.computeIfAbsent(techId, k -> new ArrayList<>())
                               .add(new CertificationRecord(code, exp));
                });

        // ── Statement 3: shift (availability) windows ─────────────────────────
        Map<UUID, List<TechnicianCandidate.AvailabilityWindow>> windowsByTech = new HashMap<>();
        namedJdbc.query("""
                SELECT aw.technician_id::text,
                       aw.day_of_week, aw.start_time, aw.end_time,
                       aw.effective_from, aw.effective_to
                FROM technician_availability_window aw
                WHERE aw.technician_id::text IN (:ids)
                """,
                idsParam,
                (rs) -> {
                    UUID      techId = UUID.fromString(rs.getString(1));
                    int       dow    = rs.getInt(2);
                    LocalTime st     = rs.getTime(3).toLocalTime();
                    LocalTime et     = rs.getTime(4).toLocalTime();
                    LocalDate ef     = rs.getDate(5).toLocalDate();
                    LocalDate eto    = rs.getDate(6) != null ? rs.getDate(6).toLocalDate() : null;
                    windowsByTech.computeIfAbsent(techId, k -> new ArrayList<>())
                                 .add(new TechnicianCandidate.AvailabilityWindow(dow, st, et, ef, eto));
                });

        // ── Statement 4: absences overlapping the service window ──────────────
        Map<UUID, List<TechnicianCandidate.Absence>> absencesByTech = new HashMap<>();
        MapSqlParameterSource absenceParams = new MapSqlParameterSource()
                .addValue("ids",         idStrings)
                .addValue("windowStart", Timestamp.from(windowStart))
                .addValue("windowEnd",   Timestamp.from(windowEnd));
        namedJdbc.query("""
                SELECT ta.technician_id::text, ta.starts_at, ta.ends_at
                FROM technician_absence ta
                WHERE ta.technician_id::text IN (:ids)
                  AND ta.ends_at   > :windowStart
                  AND ta.starts_at < :windowEnd
                """,
                absenceParams,
                (rs) -> {
                    UUID    techId = UUID.fromString(rs.getString(1));
                    Instant start  = rs.getTimestamp(2).toInstant();
                    Instant end    = rs.getTimestamp(3).toInstant();
                    absencesByTech.computeIfAbsent(techId, k -> new ArrayList<>())
                                  .add(new TechnicianCandidate.Absence(start, end));
                });

        // ── Assemble TechnicianCandidate objects ──────────────────────────────
        List<TechnicianCandidate> candidates = new ArrayList<>(techRows.size());
        for (Object[] row : techRows) {
            UUID    id       = UUID.fromString((String) row[0]);
            boolean active   = (Boolean) row[1];
            String  timezone = (String)  row[2];
            Double  lat      = row[3] != null ? ((Number) row[3]).doubleValue() : null;
            Double  lon      = row[4] != null ? ((Number) row[4]).doubleValue() : null;

            candidates.add(new TechnicianCandidate(
                    id, active, timezone,
                    certsByTech.getOrDefault(id, List.of()),
                    windowsByTech.getOrDefault(id, List.of()),
                    absencesByTech.getOrDefault(id, List.of()),
                    lat, lon));
        }
        return candidates;
    }
}

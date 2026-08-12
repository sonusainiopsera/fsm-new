package com.fieldservice.dispatch.eligibility;

import com.fieldservice.dispatch.api.EligibilityDataException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dispatch-owned read repository that loads {@link TechnicianCandidate} snapshots
 * in exactly three JDBC round-trips regardless of candidate pool size (no N+1).
 *
 * <p>Query 1 — technicians with certifications (one row per active cert, or one row
 * with null cert columns when the technician has no certs). Includes the home-base
 * site's coordinates via a LEFT JOIN on {@code site}.
 *
 * <p>Query 2 — availability windows for the loaded technician IDs (IN batch).
 *
 * <p>Query 3 — absences that overlap the service window for the loaded IDs (IN batch).
 *
 * <p>All queries are parameterized; no string concatenation in SQL.
 */
@Repository
class CandidateReadRepository {

    // ── Query 1: candidates + certifications ─────────────────────────────────

    private static final String CANDIDATES_SQL = """
            SELECT
                t.id::text              AS tid,
                t.is_active             AS active,
                t.timezone,
                s.latitude              AS home_lat,
                s.longitude             AS home_lon,
                ct.code                 AS cert_code,
                tc.expires_on::text     AS cert_expires_on
            FROM technician t
            LEFT JOIN site s
                ON s.id = t.home_base_site_id
            LEFT JOIN technician_certification tc
                ON tc.technician_id = t.id AND tc.active = true
            LEFT JOIN certification_type ct
                ON ct.id = tc.certification_type_id
            WHERE t.is_active = true
            ORDER BY t.id ASC
            LIMIT :cap
            """;

    // ── Query 2: availability windows ────────────────────────────────────────

    private static final String WINDOWS_SQL = """
            SELECT
                technician_id::text     AS tid,
                day_of_week,
                start_time::text        AS start_time,
                end_time::text          AS end_time,
                effective_from::text    AS eff_from,
                effective_to::text      AS eff_to
            FROM technician_availability_window
            WHERE technician_id = ANY(CAST(:ids AS uuid[]))
            ORDER BY technician_id, day_of_week
            """;

    // ── Query 3: absences overlapping the service window ─────────────────────

    private static final String ABSENCES_SQL = """
            SELECT
                technician_id::text     AS tid,
                starts_at,
                ends_at
            FROM technician_absence
            WHERE technician_id = ANY(CAST(:ids AS uuid[]))
              AND starts_at < :windowEnd
              AND ends_at   > :windowStart
            """;

    @PersistenceContext
    private EntityManager em;

    /**
     * Loads up to {@code cap} active technicians with their certifications, availability
     * windows, and any absences that overlap {@code [windowStart, windowEnd)}.
     *
     * @param cap           server-enforced maximum candidate count
     * @param windowStart   start of the service window (for absence pre-filter)
     * @param windowEnd     end of the service window (for absence pre-filter)
     * @return ordered list of candidate snapshots; never null
     * @throws EligibilityDataException on any database failure
     */
    List<TechnicianCandidate> loadCandidates(int cap, Instant windowStart, Instant windowEnd) {
        try {
            return doLoad(cap, windowStart, windowEnd);
        } catch (EligibilityDataException e) {
            throw e;
        } catch (Exception e) {
            throw new EligibilityDataException(
                    "Failed to load dispatch candidates from the database", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<TechnicianCandidate> doLoad(int cap, Instant windowStart, Instant windowEnd) {

        // ── Query 1: candidates + certifications ──────────────────────────────
        List<Object[]> candidateRows = em.createNativeQuery(CANDIDATES_SQL)
                .setParameter("cap", cap)
                .getResultList();

        if (candidateRows.isEmpty()) {
            return List.of();
        }

        // Group by technician ID, preserving insertion order (ORDER BY t.id ASC)
        LinkedHashMap<UUID, CandidateBuilder> builders = new LinkedHashMap<>();
        for (Object[] row : candidateRows) {
            UUID tid = UUID.fromString((String) row[0]);
            builders.computeIfAbsent(tid, id -> new CandidateBuilder(
                    id,
                    (Boolean) row[1],
                    (String) row[2],
                    toBigDecimalDouble(row[3]),
                    toBigDecimalDouble(row[4])
            ));
            if (row[5] != null) {
                LocalDate expiresOn = row[6] != null ? LocalDate.parse((String) row[6]) : null;
                builders.get(tid).addCert((String) row[5], expiresOn);
            }
        }

        // Convert IDs to PostgreSQL array literal: {uuid1,uuid2,...}
        Set<UUID> ids = builders.keySet();
        String idsLiteral = toPgArrayLiteral(ids);

        // ── Query 2: availability windows ─────────────────────────────────────
        List<Object[]> windowRows = em.createNativeQuery(WINDOWS_SQL)
                .setParameter("ids", idsLiteral)
                .getResultList();

        for (Object[] row : windowRows) {
            UUID tid = UUID.fromString((String) row[0]);
            CandidateBuilder b = builders.get(tid);
            if (b == null) continue;
            b.addWindow(
                    DayOfWeek.of(((Number) row[1]).intValue()),
                    LocalTime.parse((String) row[2]),
                    LocalTime.parse((String) row[3]),
                    LocalDate.parse((String) row[4]),
                    row[5] != null ? LocalDate.parse((String) row[5]) : null
            );
        }

        // ── Query 3: absences overlapping the service window ──────────────────
        List<Object[]> absenceRows = em.createNativeQuery(ABSENCES_SQL)
                .setParameter("ids", idsLiteral)
                .setParameter("windowStart", windowStart)
                .setParameter("windowEnd", windowEnd)
                .getResultList();

        for (Object[] row : absenceRows) {
            UUID tid = UUID.fromString((String) row[0]);
            CandidateBuilder b = builders.get(tid);
            if (b == null) continue;
            b.addAbsence(
                    ((java.sql.Timestamp) row[1]).toInstant(),
                    ((java.sql.Timestamp) row[2]).toInstant()
            );
        }

        return builders.values().stream()
                .map(CandidateBuilder::build)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Double toBigDecimalDouble(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd.doubleValue();
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }

    /** Converts a UUID collection to a PostgreSQL array literal: {@code {uuid1,uuid2,...}}. */
    private static String toPgArrayLiteral(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (UUID id : ids) {
            if (!first) sb.append(',');
            sb.append(id);   // UUID.toString() returns safe hex-and-dash chars only
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private static final class CandidateBuilder {
        private final UUID id;
        private final boolean active;
        private final String timezone;
        private final Double homeLatitude;
        private final Double homeLongitude;
        private final List<TechnicianCandidate.CertSnap> certs       = new ArrayList<>();
        private final List<TechnicianCandidate.WindowSnap> windows   = new ArrayList<>();
        private final List<TechnicianCandidate.AbsenceSnap> absences = new ArrayList<>();

        CandidateBuilder(UUID id, boolean active, String timezone,
                         Double homeLatitude, Double homeLongitude) {
            this.id            = id;
            this.active        = active;
            this.timezone      = timezone;
            this.homeLatitude  = homeLatitude;
            this.homeLongitude = homeLongitude;
        }

        void addCert(String typeCode, LocalDate expiresOn) {
            certs.add(new TechnicianCandidate.CertSnap(typeCode, expiresOn));
        }

        void addWindow(DayOfWeek dow, LocalTime start, LocalTime end,
                       LocalDate effectiveFrom, LocalDate effectiveTo) {
            windows.add(new TechnicianCandidate.WindowSnap(dow, start, end, effectiveFrom, effectiveTo));
        }

        void addAbsence(Instant startsAt, Instant endsAt) {
            absences.add(new TechnicianCandidate.AbsenceSnap(startsAt, endsAt));
        }

        TechnicianCandidate build() {
            return new TechnicianCandidate(id, active, timezone,
                    homeLatitude, homeLongitude, certs, windows, absences);
        }
    }
}

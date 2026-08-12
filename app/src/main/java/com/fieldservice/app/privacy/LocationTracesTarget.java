package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.RetentionTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RetentionTarget for the LOCATION_TRACES data category.
 *
 * <p>Operates against the {@code technician_position} table, anchored on {@code captured_at}.
 * Disposal is PHYSICAL_DELETE — no subject key exists for this category.
 *
 * <p>Phase 1: disposal is never called while {@code execution-enabled=false}.
 */
@Component
class LocationTracesTarget implements RetentionTarget {

    private final JdbcTemplate jdbc;

    LocationTracesTarget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String getDataCategory() {
        return "LOCATION_TRACES";
    }

    @Override
    public long countEligible(Instant cutoff) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM technician_position WHERE captured_at < ?",
                Long.class, Timestamp.from(cutoff));
        return count != null ? count : 0L;
    }

    @Override
    public Instant oldestEligibleAt(Instant cutoff) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MIN(captured_at) FROM technician_position WHERE captured_at < ?",
                Timestamp.class, Timestamp.from(cutoff));
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public List<UUID> pageEligibleIds(Instant cutoff, int pageSize) {
        return jdbc.queryForList(
                "SELECT id FROM technician_position WHERE captured_at < ? ORDER BY captured_at LIMIT ?",
                UUID.class, Timestamp.from(cutoff), pageSize);
    }

    @Override
    public void disposeBatch(List<UUID> ids) {
        if (ids.isEmpty()) return;
        String placeholders = ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
        jdbc.update("DELETE FROM technician_position WHERE id IN (" + placeholders + ")",
                ids.stream().map(id -> (Object) id).toArray());
    }
}

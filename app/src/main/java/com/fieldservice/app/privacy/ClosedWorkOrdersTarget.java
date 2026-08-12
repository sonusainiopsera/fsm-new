package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.RetentionTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RetentionTarget for the CLOSED_WORK_ORDERS data category.
 *
 * <p>Operates against the {@code work_order} table filtered to {@code state = 'CLOSED'},
 * anchored on {@code closed_at}. Phase 1 stub — disposal not yet invoked.
 */
@Component
class ClosedWorkOrdersTarget implements RetentionTarget {

    private final JdbcTemplate jdbc;

    ClosedWorkOrdersTarget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String getDataCategory() {
        return "CLOSED_WORK_ORDERS";
    }

    @Override
    public long countEligible(Instant cutoff) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE state = 'CLOSED' AND closed_at < ?",
                Long.class, Timestamp.from(cutoff));
        return count != null ? count : 0L;
    }

    @Override
    public Instant oldestEligibleAt(Instant cutoff) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MIN(closed_at) FROM work_order WHERE state = 'CLOSED' AND closed_at < ?",
                Timestamp.class, Timestamp.from(cutoff));
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public List<UUID> pageEligibleIds(Instant cutoff, int pageSize) {
        return jdbc.queryForList(
                "SELECT id FROM work_order WHERE state = 'CLOSED' AND closed_at < ? ORDER BY closed_at LIMIT ?",
                UUID.class, Timestamp.from(cutoff), pageSize);
    }

    @Override
    public void disposeBatch(List<UUID> ids) {
        // Phase 1 stub — work order disposal requires WO-097 CRYPTO_ERASE integration.
        // Execution flag is false by default; this method is never reached until ratified.
        throw new UnsupportedOperationException(
                "ClosedWorkOrdersTarget.disposeBatch not yet implemented — pending WO-097 CRYPTO_ERASE integration");
    }
}

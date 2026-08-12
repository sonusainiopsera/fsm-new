package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.RetentionTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RetentionTarget for the AUDIT_RECORDS data category.
 *
 * <p>Operates against the Envers {@code REVINFO} table, anchored on {@code revtstmp}
 * (epoch milliseconds). The one-year audit retention floor is enforced at the policy
 * level in {@link com.fieldservice.privacy.api.RetentionPolicyService}.
 *
 * <p>Phase 1 stub — disposal is never invoked while execution is disabled.
 */
@Component
class AuditRecordsTarget implements RetentionTarget {

    private final JdbcTemplate jdbc;

    AuditRecordsTarget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String getDataCategory() {
        return "AUDIT_RECORDS";
    }

    @Override
    public long countEligible(Instant cutoff) {
        long cutoffMs = cutoff.toEpochMilli();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM REVINFO WHERE revtstmp < ?",
                Long.class, cutoffMs);
        return count != null ? count : 0L;
    }

    @Override
    public Instant oldestEligibleAt(Instant cutoff) {
        long cutoffMs = cutoff.toEpochMilli();
        Long minMs = jdbc.queryForObject(
                "SELECT MIN(revtstmp) FROM REVINFO WHERE revtstmp < ?",
                Long.class, cutoffMs);
        return minMs != null ? Instant.ofEpochMilli(minMs) : null;
    }

    @Override
    public List<UUID> pageEligibleIds(Instant cutoff, int pageSize) {
        // REVINFO uses integer revision numbers, not UUIDs; paging not supported in Phase 1.
        throw new UnsupportedOperationException(
                "AuditRecordsTarget.pageEligibleIds not yet implemented — REVINFO uses integer PKs");
    }

    @Override
    public void disposeBatch(List<UUID> ids) {
        throw new UnsupportedOperationException(
                "AuditRecordsTarget.disposeBatch not yet implemented — pending legal sign-off on audit record deletion");
    }
}

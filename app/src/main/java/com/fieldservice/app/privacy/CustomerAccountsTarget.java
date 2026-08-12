package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.RetentionTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RetentionTarget for the CUSTOMER_ACCOUNTS data category.
 *
 * <p>Operates against the {@code customer} table, anchored on {@code deactivated_at}.
 * Only deactivated accounts (non-null {@code deactivated_at}) are eligible.
 * Phase 1 stub — disposal pending WO-097 CRYPTO_ERASE integration.
 */
@Component
class CustomerAccountsTarget implements RetentionTarget {

    private final JdbcTemplate jdbc;

    CustomerAccountsTarget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String getDataCategory() {
        return "CUSTOMER_ACCOUNTS";
    }

    @Override
    public long countEligible(Instant cutoff) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer WHERE deactivated_at IS NOT NULL AND deactivated_at < ?",
                Long.class, Timestamp.from(cutoff));
        return count != null ? count : 0L;
    }

    @Override
    public Instant oldestEligibleAt(Instant cutoff) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MIN(deactivated_at) FROM customer WHERE deactivated_at IS NOT NULL AND deactivated_at < ?",
                Timestamp.class, Timestamp.from(cutoff));
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public List<UUID> pageEligibleIds(Instant cutoff, int pageSize) {
        return jdbc.queryForList(
                "SELECT id FROM customer WHERE deactivated_at IS NOT NULL AND deactivated_at < ? ORDER BY deactivated_at LIMIT ?",
                UUID.class, Timestamp.from(cutoff), pageSize);
    }

    @Override
    public void disposeBatch(List<UUID> ids) {
        throw new UnsupportedOperationException(
                "CustomerAccountsTarget.disposeBatch not yet implemented — pending WO-097 CRYPTO_ERASE integration");
    }
}

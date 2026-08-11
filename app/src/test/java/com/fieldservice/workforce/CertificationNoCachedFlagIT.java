package com.fieldservice.workforce;

import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema assertion: fails the build if a cached-currency-flag column appears on
 * either certification table (AC-2 of WO-119).
 *
 * <p>Rule: no column named {@code is_current}, {@code is_valid}, {@code current_flag}
 * or {@code valid_flag} may exist on {@code certification_type} or
 * {@code technician_certification}. Currency is always a query predicate, never stored.
 */
@DisplayName("Certification schema — no cached currency flag columns (AC-2)")
class CertificationNoCachedFlagIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("certification_type has no cached-currency-flag column")
    void certificationTypeTable_hasNoCachedFlag() {
        assertNoCachedFlagColumn("certification_type");
    }

    @Test
    @DisplayName("technician_certification has no cached-currency-flag column")
    void technicianCertificationTable_hasNoCachedFlag() {
        assertNoCachedFlagColumn("technician_certification");
    }

    private void assertNoCachedFlagColumn(String tableName) {
        List<String> flagColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_name   = ?
                   AND column_name IN ('is_current','is_valid','current_flag','valid_flag')
                """,
                String.class,
                tableName);

        assertThat(flagColumns)
                .as("Table '%s' must not have a cached currency flag column but found: %s",
                        tableName, flagColumns)
                .isEmpty();
    }
}

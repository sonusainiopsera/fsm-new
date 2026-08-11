package com.fieldservice.app.schema;

import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DatabaseCleaner} table ordering and reference-data exclusion.
 * These run without any container.
 */
class DatabaseCleanerTest {

    @Test
    @DisplayName("reference tables sla_policy and hold_reason are absent from the truncation list")
    void reference_tables_are_excluded() {
        List<String> tables = DatabaseCleaner.MUTABLE_TABLES;

        assertThat(tables).doesNotContain("sla_policy");
        assertThat(tables).doesNotContain("hold_reason");
    }

    @Test
    @DisplayName("stock_ledger and work_order_part appear before work_order (child before parent)")
    void child_tables_precede_parent_tables() {
        List<String> tables = DatabaseCleaner.MUTABLE_TABLES;

        int stockLedgerIdx     = tables.indexOf("stock_ledger");
        int workOrderPartIdx   = tables.indexOf("work_order_part");
        int workOrderIdx       = tables.indexOf("work_order");

        assertThat(stockLedgerIdx).as("stock_ledger index").isGreaterThanOrEqualTo(0);
        assertThat(workOrderPartIdx).as("work_order_part index").isGreaterThanOrEqualTo(0);
        assertThat(workOrderIdx).as("work_order index").isGreaterThanOrEqualTo(0);

        assertThat(stockLedgerIdx).isLessThan(workOrderIdx);
        assertThat(workOrderPartIdx).isLessThan(workOrderIdx);
    }

    @Test
    @DisplayName("site appears after work_order (parent after child)")
    void parent_tables_follow_children() {
        List<String> tables = DatabaseCleaner.MUTABLE_TABLES;

        int workOrderIdx = tables.indexOf("work_order");
        int siteIdx      = tables.indexOf("site");

        assertThat(workOrderIdx).as("work_order index").isGreaterThanOrEqualTo(0);
        assertThat(siteIdx).as("site index").isGreaterThanOrEqualTo(0);
        assertThat(workOrderIdx).isLessThan(siteIdx);
    }

    @Test
    @DisplayName("outbox_event and idempotency_key are present")
    void transactional_support_tables_are_present() {
        assertThat(DatabaseCleaner.MUTABLE_TABLES).contains("outbox_event", "idempotency_key");
    }
}

package com.fieldservice.privacy;

import com.fieldservice.privacy.api.DryRunReport;
import com.fieldservice.privacy.api.RetentionPolicyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public-API smoke test for retention policy record types (WO-189).
 *
 * <p>Detailed cut-off arithmetic and guard tests are in
 * {@code com.fieldservice.privacy.internal.RetentionPolicyCutoffTest}
 * where package-private methods are accessible.
 */
@DisplayName("Retention policy public record types")
class RetentionPolicyCutoffTest {

    @Test
    @DisplayName("DryRunReport can be constructed and accessed")
    void dryRunReport_canBeConstructed() {
        var report = new DryRunReport(
                "LOCATION_TRACES",
                java.time.Instant.parse("2025-03-17T12:00:00Z"),
                42L,
                null,
                "PHYSICAL_DELETE",
                java.util.List.of()
        );
        assertThat(report.dataCategory()).isEqualTo("LOCATION_TRACES");
        assertThat(report.eligibleCount()).isEqualTo(42L);
        assertThat(report.skippedReasons()).isEmpty();
    }

    @Test
    @DisplayName("RetentionPolicyView can be constructed and accessed")
    void retentionPolicyView_canBeConstructed() {
        var id = java.util.UUID.randomUUID();
        var view = new RetentionPolicyView(
                id, "CLOSED_WORK_ORDERS", "WorkOrder",
                5, "YEARS", "updated_at",
                "PHYSICAL_DELETE", false, false, false,
                "PLACEHOLDER", 0, null, null
        );
        assertThat(view.dataCategory()).isEqualTo("CLOSED_WORK_ORDERS");
        assertThat(view.periodValue()).isEqualTo(5);
        assertThat(view.periodUnit()).isEqualTo("YEARS");
        assertThat(view.disposalMethod()).isEqualTo("PHYSICAL_DELETE");
        assertThat(view.ratified()).isFalse();
    }
}

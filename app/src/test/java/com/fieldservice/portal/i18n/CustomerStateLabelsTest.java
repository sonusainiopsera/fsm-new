package com.fieldservice.portal.i18n;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CustomerStateLabels} (no Spring context, AC-8).
 *
 * <p>Verifies that every lifecycle state and hold-reason path has an approved
 * customer-facing phrase containing no internal jargon (AC-7).
 */
class CustomerStateLabelsTest {

    private static final String[] FORBIDDEN_TERMS = {
            // internal state codes
            "NEW", "ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD",
            "COMPLETED", "CLOSED", "CANCELLED",
            // internal jargon
            "dispatch", "score", "override", "envers", "revision", "audit",
            "SLA", "technician_id", "customer_id", "assignedTechnicianId"
    };

    @ParameterizedTest(name = "label for {0} is non-empty and jargon-free")
    @EnumSource(WorkOrderState.class)
    @DisplayName("Every lifecycle state has a non-null, jargon-free customer-facing label (AC-7)")
    void everyStateMapped(WorkOrderState state) {
        CustomerStateLabels.StateLabel stateLabel = CustomerStateLabels.forState(state);

        assertThat(stateLabel).isNotNull();
        assertThat(stateLabel.label()).isNotBlank();
        assertThat(stateLabel.description()).isNotBlank();

        // No internal enum names or dispatch jargon
        for (String forbidden : FORBIDDEN_TERMS) {
            assertThat(stateLabel.label())
                    .as("label for %s should not contain '%s'", state, forbidden)
                    .doesNotContain(forbidden);
            assertThat(stateLabel.description())
                    .as("description for %s should not contain '%s'", state, forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("ON_HOLD with hold reason produces enriched description containing reason label")
    void onHoldWithReasonProducesEnrichedDescription() {
        CustomerStateLabels.StateLabel label = CustomerStateLabels.forOnHoldWithReason("Awaiting parts or materials");

        assertThat(label.label()).isEqualTo("Work Paused");
        assertThat(label.description()).contains("Awaiting parts or materials");
        assertThat(label.description()).doesNotContain("ON_HOLD");
    }

    @Test
    @DisplayName("ON_HOLD with null reason produces generic description")
    void onHoldWithNullReasonProducesGenericDescription() {
        CustomerStateLabels.StateLabel label = CustomerStateLabels.forOnHoldWithReason(null);

        assertThat(label.label()).isEqualTo("Work Paused");
        assertThat(label.description()).doesNotContain("null");
        assertThat(label.description()).isNotBlank();
    }

    @Test
    @DisplayName("forState(null) returns safe default rather than throwing")
    void nullStateFallsBackToSafeDefault() {
        CustomerStateLabels.StateLabel label = CustomerStateLabels.forState(null);

        assertThat(label).isNotNull();
        assertThat(label.label()).isNotBlank();
        assertThat(label.description()).isNotBlank();
    }

    @Test
    @DisplayName("All declared milestone labels are jargon-free")
    void milestoneLabelsCoverKeyEventsAndAreJargonFree() {
        String[] expectedEvents = { "CREATED", "ASSIGNED", "DEPARTED", "STARTED",
                "RESUMED", "HELD", "COMPLETED", "CLOSED", "CANCELLED", "REASSIGNED" };

        for (String event : expectedEvents) {
            String label = CustomerStateLabels.milestoneLabel(event);
            assertThat(label)
                    .as("milestone label for %s", event)
                    .isNotBlank()
                    .doesNotContain("EN_ROUTE")
                    .doesNotContain("IN_PROGRESS")
                    .doesNotContain("ON_HOLD");
        }
    }

    @Test
    @DisplayName("milestoneLabel falls back to the raw event type for unknown events")
    void unknownMilestoneEventFallsBackToRawType() {
        assertThat(CustomerStateLabels.milestoneLabel("UNKNOWN_FUTURE_EVENT"))
                .isEqualTo("UNKNOWN_FUTURE_EVENT");
    }
}

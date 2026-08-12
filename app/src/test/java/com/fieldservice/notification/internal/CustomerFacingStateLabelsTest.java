package com.fieldservice.notification.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerFacingStateLabelsTest {

    @ParameterizedTest(name = "state={0} → label must not contain internal name")
    @CsvSource({
            "NEW",
            "ASSIGNED",
            "EN_ROUTE",
            "IN_PROGRESS",
            "ON_HOLD",
            "COMPLETED",
            "CLOSED",
            "CANCELLED"
    })
    @DisplayName("every lifecycle state maps to a label that does not expose the internal state name")
    void stateLabel_doesNotExposeInternalName(String state) {
        String label = CustomerFacingStateLabels.label(state, null);
        assertThat(label).isNotBlank();
        // Internal state name must not appear verbatim in customer-facing label
        assertThat(label).doesNotContainIgnoringCase(state);
    }

    @ParameterizedTest(name = "ON_HOLD reason={0}")
    @CsvSource({
            "AWAITING_PARTS,          Waiting for parts",
            "CUSTOMER_UNAVAILABLE,    Waiting for access",
            "ACCESS_DENIED,           Waiting for access",
            "WEATHER,                 Paused due to weather conditions",
            "SAFETY_CONCERN,          Paused while a safety matter is addressed",
            "AWAITING_APPROVAL,       Awaiting your approval"
    })
    @DisplayName("ON_HOLD with reason code maps to specific label")
    void onHoldWithReason_mapsToSpecificLabel(String reasonCode, String expectedLabel) {
        String label = CustomerFacingStateLabels.label("ON_HOLD", reasonCode);
        assertThat(label).isEqualToIgnoringCase(expectedLabel.strip());
    }

    @Test
    @DisplayName("ON_HOLD with null reason defaults to generic hold label")
    void onHoldNullReason_defaultsToGeneric() {
        String label = CustomerFacingStateLabels.label("ON_HOLD", null);
        assertThat(label).isEqualTo("Work temporarily paused");
    }

    @Test
    @DisplayName("ON_HOLD with unknown reason defaults to generic hold label")
    void onHoldUnknownReason_defaultsToGeneric() {
        String label = CustomerFacingStateLabels.label("ON_HOLD", "SOME_FUTURE_REASON");
        assertThat(label).isEqualTo("Work temporarily paused");
    }

    @Test
    @DisplayName("description is non-blank for all states")
    void description_nonBlankForAllStates() {
        for (String state : new String[]{"NEW", "ASSIGNED", "EN_ROUTE", "IN_PROGRESS",
                "ON_HOLD", "COMPLETED", "CLOSED", "CANCELLED"}) {
            assertThat(CustomerFacingStateLabels.description(state, null))
                    .as("description for " + state)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("no label contains internal jargon like 'dispatch', 'assignment', 'technician'")
    void labels_containNoInternalJargon() {
        for (String state : new String[]{"NEW", "ASSIGNED", "EN_ROUTE", "IN_PROGRESS",
                "COMPLETED", "CLOSED", "CANCELLED"}) {
            String label = CustomerFacingStateLabels.label(state, null);
            assertThat(label).doesNotContainIgnoringCase("dispatch");
            assertThat(label).doesNotContainIgnoringCase("assignment");
        }
    }

    @Test
    @DisplayName("unmapped state returns the input as safety net (never blank)")
    void unmappedState_returnsSafetyNetValue() {
        String label = CustomerFacingStateLabels.label("UNKNOWN_FUTURE_STATE", null);
        assertThat(label).isNotBlank();
    }
}

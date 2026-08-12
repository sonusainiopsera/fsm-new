package com.fieldservice.notification.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CustomerFacingStateLabels (WO-196, AC-8).
 *
 * <p>Verifies that internal state names are never surfaced in customer channels,
 * and that every customer-visible state maps to a customer-appropriate label.
 */
class CustomerFacingStateLabelsTest {

    @ParameterizedTest(name = "state {0} is customer-visible")
    @ValueSource(strings = {"ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD", "COMPLETED", "CLOSED", "CANCELLED"})
    void customer_visible_states_have_labels(String state) {
        assertThat(CustomerFacingStateLabels.isCustomerVisible(state)).isTrue();
        assertThat(CustomerFacingStateLabels.labelFor(state)).isPresent();
    }

    @Test
    void NEW_is_not_customer_visible() {
        assertThat(CustomerFacingStateLabels.isCustomerVisible("NEW")).isFalse();
        assertThat(CustomerFacingStateLabels.labelFor("NEW")).isEmpty();
    }

    @ParameterizedTest(name = "label for {0} does not contain internal state name")
    @ValueSource(strings = {"ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD", "COMPLETED", "CLOSED", "CANCELLED"})
    void labels_do_not_expose_internal_state_names(String state) {
        String label = CustomerFacingStateLabels.labelFor(state).orElseThrow();
        // The label must not be the raw enum name
        assertThat(label).isNotEqualToIgnoringCase(state);
    }

    @Test
    void assigned_label_uses_customer_appropriate_wording() {
        String label = CustomerFacingStateLabels.labelFor("ASSIGNED").orElseThrow();
        assertThat(label).containsIgnoringCase("assigned");
        assertThat(label).doesNotContain("ASSIGNED"); // no raw enum name
    }

    @Test
    void in_progress_label_does_not_expose_internal_name() {
        String label = CustomerFacingStateLabels.labelFor("IN_PROGRESS").orElseThrow();
        assertThat(label).doesNotContain("IN_PROGRESS");
    }

    @Test
    void unknown_state_returns_empty() {
        assertThat(CustomerFacingStateLabels.isCustomerVisible("UNKNOWN_STATE")).isFalse();
        assertThat(CustomerFacingStateLabels.labelFor("UNKNOWN_STATE")).isEmpty();
    }

    @Test
    void all_labels_are_non_blank() {
        for (String state : new String[]{"ASSIGNED", "EN_ROUTE", "IN_PROGRESS", "ON_HOLD",
                "COMPLETED", "CLOSED", "CANCELLED"}) {
            assertThat(CustomerFacingStateLabels.labelFor(state).orElseThrow())
                    .as("label for %s must be non-blank", state)
                    .isNotBlank();
        }
    }
}

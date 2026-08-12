package com.fieldservice.workorder.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AllowedTransitionResolver — verifies that TECHNICIAN-allowed events
 * match the transition table per state, and that DISPATCHER-only events are excluded.
 */
class AllowedTransitionResolverTest {

    private final AllowedTransitionResolver resolver = new AllowedTransitionResolver();

    @Test
    @DisplayName("ASSIGNED → TECHNICIAN may only DEPART (not UNASSIGN or CANCEL)")
    void assigned_technicianMayDepart() {
        List<String> events = resolver.allowedEvents(WorkOrderState.ASSIGNED, "TECHNICIAN");
        assertThat(events).contains("DEPART");
        assertThat(events).doesNotContain("UNASSIGN", "CANCEL", "ASSIGN");
    }

    @Test
    @DisplayName("EN_ROUTE → TECHNICIAN may only START")
    void enRoute_technicianMayStart() {
        List<String> events = resolver.allowedEvents(WorkOrderState.EN_ROUTE, "TECHNICIAN");
        assertThat(events).containsExactlyInAnyOrder("START");
    }

    @Test
    @DisplayName("IN_PROGRESS → TECHNICIAN may HOLD and COMPLETE")
    void inProgress_technicianMayHoldOrComplete() {
        List<String> events = resolver.allowedEvents(WorkOrderState.IN_PROGRESS, "TECHNICIAN");
        assertThat(events).containsExactlyInAnyOrder("COMPLETE", "HOLD");
    }

    @Test
    @DisplayName("ON_HOLD → TECHNICIAN may only RESUME")
    void onHold_technicianMayResume() {
        List<String> events = resolver.allowedEvents(WorkOrderState.ON_HOLD, "TECHNICIAN");
        assertThat(events).containsExactlyInAnyOrder("RESUME");
    }

    @Test
    @DisplayName("COMPLETED → TECHNICIAN has no allowed events (CLOSE is DISPATCHER only)")
    void completed_technicianHasNoEvents() {
        List<String> events = resolver.allowedEvents(WorkOrderState.COMPLETED, "TECHNICIAN");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("CLOSED → empty for all roles")
    void closed_alwaysEmpty() {
        assertThat(resolver.allowedEvents(WorkOrderState.CLOSED, "TECHNICIAN")).isEmpty();
        assertThat(resolver.allowedEvents(WorkOrderState.CLOSED, "DISPATCHER")).isEmpty();
    }

    @Test
    @DisplayName("CANCELLED → empty for all roles")
    void cancelled_alwaysEmpty() {
        assertThat(resolver.allowedEvents(WorkOrderState.CANCELLED, "TECHNICIAN")).isEmpty();
    }

    @Test
    @DisplayName("DISPATCHER may CLOSE from COMPLETED, TECHNICIAN may not")
    void completed_dispatcherMayCloseButTechnicianMayNot() {
        assertThat(resolver.allowedEvents(WorkOrderState.COMPLETED, "DISPATCHER"))
                .contains("CLOSE");
        assertThat(resolver.allowedEvents(WorkOrderState.COMPLETED, "TECHNICIAN"))
                .doesNotContain("CLOSE");
    }

    @Test
    @DisplayName("NEW → DISPATCHER may ASSIGN, TECHNICIAN may not")
    void newState_technicianCannotAssign() {
        List<String> techEvents = resolver.allowedEvents(WorkOrderState.NEW, "TECHNICIAN");
        assertThat(techEvents).doesNotContain("ASSIGN");
        assertThat(resolver.allowedEvents(WorkOrderState.NEW, "DISPATCHER")).contains("ASSIGN");
    }
}

package com.fieldservice.workorder.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.*;
import static com.fieldservice.workorder.lifecycle.WorkOrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the transition table itself: resolution, role gating, terminal-state
 * immutability, exception payload, and table immutability. No Spring context required.
 */
class TransitionTableUnitTest {

    // ---- Resolution ------------------------------------------------------------

    @Test
    @DisplayName("resolve returns correct descriptor for a known transition")
    void resolve_knownTransition() {
        Optional<TransitionDescriptor> result = WorkOrderTransitionTable.resolve(NEW, ASSIGN);
        assertThat(result).isPresent();
        assertThat(result.get().toState()).isEqualTo(ASSIGNED);
    }

    @Test
    @DisplayName("resolve returns empty for an unknown (from, event) pair")
    void resolve_unknownTransition_empty() {
        Optional<TransitionDescriptor> result = WorkOrderTransitionTable.resolve(CLOSED, ASSIGN);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("legalEventsFrom returns all events defined from a state")
    void legalEventsFrom_includesAllDefinedEvents() {
        Set<WorkOrderEvent> events = WorkOrderTransitionTable.legalEventsFrom(NEW);
        assertThat(events).containsExactlyInAnyOrder(ASSIGN, CANCEL);
    }

    @Test
    @DisplayName("legalEventsFrom returns empty set for terminal CLOSED state")
    void legalEventsFrom_closedIsEmpty() {
        assertThat(WorkOrderTransitionTable.legalEventsFrom(CLOSED)).isEmpty();
    }

    @Test
    @DisplayName("legalEventsFrom returns empty set for terminal CANCELLED state")
    void legalEventsFrom_cancelledIsEmpty() {
        assertThat(WorkOrderTransitionTable.legalEventsFrom(CANCELLED)).isEmpty();
    }

    // ---- Role gating -----------------------------------------------------------

    @Test
    @DisplayName("ASSIGN transition requires DISPATCHER or ADMIN role")
    void assign_requiresDispatcherOrAdmin() {
        TransitionDescriptor d = WorkOrderTransitionTable.resolve(NEW, ASSIGN).orElseThrow();
        assertThat(d.requiredRoles()).contains("DISPATCHER", "ADMIN");
        assertThat(d.requiredRoles()).doesNotContain("TECHNICIAN", "CUSTOMER");
    }

    @Test
    @DisplayName("DEPART transition is permitted for TECHNICIAN")
    void depart_permittedForTechnician() {
        TransitionDescriptor d = WorkOrderTransitionTable.resolve(ASSIGNED, DEPART).orElseThrow();
        assertThat(d.requiredRoles()).contains("TECHNICIAN");
    }

    @Test
    @DisplayName("CANCEL transition does not allow TECHNICIAN role")
    void cancel_notPermittedForTechnician() {
        TransitionDescriptor d = WorkOrderTransitionTable.resolve(NEW, CANCEL).orElseThrow();
        assertThat(d.requiredRoles()).doesNotContain("TECHNICIAN");
    }

    // ---- Terminal-state immutability -------------------------------------------

    @Test
    @DisplayName("every event applied to CLOSED is refused")
    void closed_allEventsRefused() {
        for (WorkOrderEvent e : WorkOrderEvent.values()) {
            assertThat(WorkOrderTransitionTable.resolve(CLOSED, e))
                    .as("CLOSED + %s should be refused", e)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("every event applied to CANCELLED is refused")
    void cancelled_allEventsRefused() {
        for (WorkOrderEvent e : WorkOrderEvent.values()) {
            assertThat(WorkOrderTransitionTable.resolve(CANCELLED, e))
                    .as("CANCELLED + %s should be refused", e)
                    .isEmpty();
        }
    }

    // ---- IllegalWorkOrderTransitionException payload ---------------------------

    @Test
    @DisplayName("IllegalWorkOrderTransitionException carries correct fields and error code")
    void illegalTransitionException_payloadCorrect() {
        Set<WorkOrderEvent> legal = Set.of(ASSIGN, CANCEL);
        IllegalWorkOrderTransitionException ex =
                new IllegalWorkOrderTransitionException(NEW, DEPART, legal);

        assertThat(ex.getCurrentState()).isEqualTo(NEW);
        assertThat(ex.getRequestedEvent()).isEqualTo(DEPART);
        assertThat(ex.getLegalEvents()).containsExactlyInAnyOrderElementsOf(legal);
        assertThat(ex.getErrorCode()).isEqualTo(com.fieldservice.workorder.WorkOrderErrorCodes.ILLEGAL_TRANSITION);
        assertThat(ex.getMessage()).contains("DEPART").contains("NEW");
    }

    // ---- Table immutability ----------------------------------------------------

    @Test
    @DisplayName("rawTable() map is unmodifiable — put throws")
    void rawTable_throwsOnMutate() {
        Map<TransitionKey, TransitionDescriptor> table = WorkOrderTransitionTable.rawTable();
        assertThatThrownBy(() -> table.put(
                new TransitionKey(CLOSED, CANCEL),
                new TransitionDescriptor(NEW, Set.of("ADMIN"), List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("requiredRoles set in each descriptor is unmodifiable — add throws")
    void descriptor_rolesUnmodifiable() {
        TransitionDescriptor d = WorkOrderTransitionTable.resolve(NEW, ASSIGN).orElseThrow();
        assertThatThrownBy(() -> d.requiredRoles().add("CUSTOMER"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("guardIds list in each descriptor is unmodifiable — add throws")
    void descriptor_guardIdsUnmodifiable() {
        TransitionDescriptor d = WorkOrderTransitionTable.resolve(NEW, ASSIGN).orElseThrow();
        assertThatThrownBy(() -> d.guardIds().add("some-guard"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- Table size guard ------------------------------------------------------

    @Test
    @DisplayName("table contains exactly 13 entries matching ADR-0007")
    void table_exactlyThirteenEntries() {
        assertThat(WorkOrderTransitionTable.rawTable()).hasSize(13);
    }
}

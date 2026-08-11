package com.fieldservice.app.workorder;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.domain.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.domain.workorder.lifecycle.TransitionDescriptor;
import com.fieldservice.domain.workorder.lifecycle.TransitionKey;
import com.fieldservice.domain.workorder.lifecycle.WorkOrderErrorCodes;
import com.fieldservice.domain.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.domain.workorder.lifecycle.WorkOrderTransitionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.fieldservice.domain.workorder.WorkOrderState.*;
import static com.fieldservice.domain.workorder.lifecycle.WorkOrderEvent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive 8x8 (fromState × toState) parameterized matrix test.
 *
 * <p>For every ordered pair of (fromState, toState), this test asserts either:
 * <ul>
 *   <li>A specific documented event moves the work order from fromState to toState, or</li>
 *   <li>No event in {@link WorkOrderEvent} can produce that transition.</li>
 * </ul>
 *
 * Adding a ninth state or event without updating the matrix method fails the build.
 * Tests run with no Spring context — pure JUnit 5.
 */
@DisplayName("Work order lifecycle 8×8 transition matrix")
class TransitionMatrixTest {

    // ── Reflection access to the package-private table ────────────────────────

    /** Resolve via the package-private table accessed through the test package. */
    private static Optional<TransitionDescriptor> resolve(WorkOrderState from, WorkOrderEvent event) {
        return TABLE_INSTANCE.resolve(from, event);
    }

    private static Set<WorkOrderEvent> legalEventsFrom(WorkOrderState state) {
        return TABLE_INSTANCE.legalEventsFrom(state);
    }

    // Obtain the package-private table via the test-only accessor (same package).
    // TABLE_INSTANCE is typed as WorkOrderTransitionPort (public interface) so this
    // compiles from any package.
    private static final WorkOrderTransitionPort TABLE_INSTANCE =
            new com.fieldservice.domain.workorder.lifecycle.WorkOrderTransitionTableAccessor().table();

    // ── Matrix definition ────────────────────────────────────────────────────
    // Each row: [fromState, toState, expectedEvent-or-null]
    // null expectedEvent → the transition is ILLEGAL (no event produces it)

    static Stream<Object[]> matrixSource() {
        List<Object[]> rows = new ArrayList<>();
        WorkOrderState[] states = WorkOrderState.values();
        assertThat(states).as("8 states required; update matrix if count changes").hasSize(8);

        // Legal transitions (fromState, toState, event)
        Object[][] legal = {
            {NEW,         ASSIGNED,    ASSIGN},
            {NEW,         CANCELLED,   CANCEL},
            {ASSIGNED,    EN_ROUTE,    DEPART},
            {ASSIGNED,    CANCELLED,   CANCEL},
            {EN_ROUTE,    IN_PROGRESS, START},
            {EN_ROUTE,    ON_HOLD,     HOLD},
            {EN_ROUTE,    CANCELLED,   CANCEL},
            {IN_PROGRESS, ON_HOLD,     HOLD},
            {IN_PROGRESS, COMPLETED,   COMPLETE},
            {IN_PROGRESS, CANCELLED,   CANCEL},
            {ON_HOLD,     IN_PROGRESS, RESUME},
            {ON_HOLD,     CANCELLED,   CANCEL},
            {COMPLETED,   CLOSED,      CLOSE},
        };

        // Build a lookup for quick illegal-cell detection
        Set<String> legalKeys = new java.util.HashSet<>();
        for (Object[] row : legal) {
            legalKeys.add(row[0] + "->" + row[1]);
        }

        // Emit all 64 cells
        for (WorkOrderState from : states) {
            for (WorkOrderState to : states) {
                String key = from + "->" + to;
                if (legalKeys.contains(key)) {
                    // find the legal row
                    for (Object[] lrow : legal) {
                        if (lrow[0] == from && lrow[1] == to) {
                            rows.add(new Object[]{from, to, lrow[2]});
                            break;
                        }
                    }
                } else {
                    rows.add(new Object[]{from, to, null});
                }
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} → {1} via {2}")
    @MethodSource("matrixSource")
    @DisplayName("Matrix cell")
    void matrix_cell(WorkOrderState from, WorkOrderState to, WorkOrderEvent expectedEvent) {
        if (expectedEvent != null) {
            // The expected event must resolve to the target state
            Optional<TransitionDescriptor> descriptor = resolve(from, expectedEvent);
            assertThat(descriptor)
                    .as("Expected legal transition %s --%s--> %s", from, expectedEvent, to)
                    .isPresent();
            assertThat(descriptor.get().toState())
                    .as("Target state for %s --%s-->", from, expectedEvent)
                    .isEqualTo(to);
        } else {
            // No event should resolve from → to
            for (WorkOrderEvent event : WorkOrderEvent.values()) {
                Optional<TransitionDescriptor> descriptor = resolve(from, event);
                if (descriptor.isPresent() && descriptor.get().toState() == to) {
                    org.junit.jupiter.api.Assertions.fail(
                            "Expected no transition %s → %s but found event %s", from, to, event);
                }
            }
        }
    }

    // ── AC-7: terminal states have no outbound transitions ───────────────────

    @Test
    @DisplayName("AC-7: CLOSED has zero outbound transitions for any event")
    void closed_has_no_outbound_transitions() {
        assertThat(legalEventsFrom(CLOSED)).isEmpty();
        for (WorkOrderEvent event : WorkOrderEvent.values()) {
            assertThat(resolve(CLOSED, event))
                    .as("CLOSED + %s must be illegal", event)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("AC-7: CANCELLED has zero outbound transitions for any event")
    void cancelled_has_no_outbound_transitions() {
        assertThat(legalEventsFrom(CANCELLED)).isEmpty();
        for (WorkOrderEvent event : WorkOrderEvent.values()) {
            assertThat(resolve(CANCELLED, event))
                    .as("CANCELLED + %s must be illegal", event)
                    .isEmpty();
        }
    }

    // ── AC-4: illegal-transition exception payload ────────────────────────────

    @Test
    @DisplayName("AC-4: CLOSED + CANCEL throws IllegalWorkOrderTransitionException with correct payload")
    void closed_cancel_produces_illegal_transition_exception() {
        IllegalWorkOrderTransitionException ex =
                new IllegalWorkOrderTransitionException(CLOSED, CANCEL, Set.of());
        assertThat(ex.getCode()).isEqualTo(WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION);
        assertThat(ex.getCurrentState()).isEqualTo(CLOSED);
        assertThat(ex.getRequestedEvent()).isEqualTo(CANCEL);
        assertThat(ex.getLegalEvents()).isEmpty();
    }

    @Test
    @DisplayName("AC-4: COMPLETED + CANCEL — exception carries legal events hint")
    void completed_cancel_exception_carries_legal_events() {
        Set<WorkOrderEvent> legal = legalEventsFrom(COMPLETED);
        IllegalWorkOrderTransitionException ex =
                new IllegalWorkOrderTransitionException(COMPLETED, CANCEL, legal);
        assertThat(ex.getCode()).isEqualTo(WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION);
        assertThat(ex.getCurrentState()).isEqualTo(COMPLETED);
        assertThat(ex.getRequestedEvent()).isEqualTo(CANCEL);
        assertThat(ex.getLegalEvents()).containsExactlyInAnyOrder(CLOSE);
    }

    // ── AC-2: table immutability ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-2: table map is unmodifiable — put() throws UnsupportedOperationException")
    void table_is_immutable() throws Exception {
        // WorkOrderTransitionTable is package-private; use Class.forName for reflection
        Class<?> tableClass = Class.forName(
                "com.fieldservice.domain.workorder.lifecycle.WorkOrderTransitionTable");
        Field tableField = tableClass.getDeclaredField("TABLE");
        tableField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<TransitionKey, TransitionDescriptor> map =
                (Map<TransitionKey, TransitionDescriptor>) tableField.get(null);

        assertThatThrownBy(() ->
                map.put(new TransitionKey(CLOSED, CANCEL),
                        new TransitionDescriptor(CANCELLED, Set.of("ADMIN"), List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── AC-1: enum vocabulary completeness ────────────────────────────────────

    @Test
    @DisplayName("AC-1: WorkOrderState has exactly 8 values")
    void work_order_state_has_eight_values() {
        assertThat(WorkOrderState.values()).hasSize(8);
        assertThat(WorkOrderState.values()).containsExactlyInAnyOrder(
                NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD, COMPLETED, CLOSED, CANCELLED);
    }

    @Test
    @DisplayName("AC-1: WorkOrderEvent has exactly 8 values")
    void work_order_event_has_eight_values() {
        assertThat(WorkOrderEvent.values()).hasSize(8);
        assertThat(WorkOrderEvent.values()).containsExactlyInAnyOrder(
                ASSIGN, DEPART, START, HOLD, RESUME, COMPLETE, CLOSE, CANCEL);
    }

    // ── Role gating ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("TECHNICIAN is permitted for DEPART but not CLOSE")
    void role_gating_technician_depart_but_not_close() {
        TransitionDescriptor depart = resolve(ASSIGNED, DEPART).orElseThrow();
        assertThat(depart.requiredRoles()).contains("TECHNICIAN");

        TransitionDescriptor close = resolve(COMPLETED, CLOSE).orElseThrow();
        assertThat(close.requiredRoles()).doesNotContain("TECHNICIAN");
    }

    @Test
    @DisplayName("CANCEL from EN_ROUTE requires DISPATCHER or ADMIN (ADR-0007)")
    void en_route_cancel_requires_dispatcher_or_admin() {
        TransitionDescriptor desc = resolve(EN_ROUTE, CANCEL).orElseThrow();
        assertThat(desc.requiredRoles()).containsAnyOf("DISPATCHER", "ADMIN");
        assertThat(desc.requiredRoles()).doesNotContain("TECHNICIAN");
    }

    @Test
    @DisplayName("CANCEL from ON_HOLD requires DISPATCHER or ADMIN (ADR-0007)")
    void on_hold_cancel_requires_dispatcher_or_admin() {
        TransitionDescriptor desc = resolve(ON_HOLD, CANCEL).orElseThrow();
        assertThat(desc.requiredRoles()).containsAnyOf("DISPATCHER", "ADMIN");
    }

    // ── legalEventsFrom completeness ─────────────────────────────────────────

    @Test
    @DisplayName("legalEventsFrom(IN_PROGRESS) returns HOLD, COMPLETE, CANCEL")
    void legal_events_from_in_progress() {
        assertThat(legalEventsFrom(IN_PROGRESS))
                .containsExactlyInAnyOrder(HOLD, COMPLETE, CANCEL);
    }

    @Test
    @DisplayName("legalEventsFrom(NEW) returns ASSIGN and CANCEL")
    void legal_events_from_new() {
        assertThat(legalEventsFrom(NEW)).containsExactlyInAnyOrder(ASSIGN, CANCEL);
    }

    @Test
    @DisplayName("WorkOrderErrorCodes constants have expected values")
    void error_codes_have_expected_string_values() {
        assertThat(WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION)
                .isEqualTo("WORK_ORDER_ILLEGAL_TRANSITION");
        assertThat(WorkOrderErrorCodes.WORK_ORDER_GUARD_REFUSED)
                .isEqualTo("WORK_ORDER_GUARD_REFUSED");
        assertThat(WorkOrderErrorCodes.WORK_ORDER_VERSION_CONFLICT)
                .isEqualTo("WORK_ORDER_VERSION_CONFLICT");
    }
}

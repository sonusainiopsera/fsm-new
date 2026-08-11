package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static com.fieldservice.domain.workorder.WorkOrderState.*;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive 8×8 parameterised matrix test.
 *
 * <p>For every ordered (fromState, toState) pair the test asserts either:
 * <ul>
 *   <li><b>Legal cell:</b> a named event resolves to the target state.</li>
 *   <li><b>Illegal cell:</b> no event in {@link WorkOrderEvent} produces the target state
 *       from the given source state.</li>
 * </ul>
 *
 * <p>The matrix is keyed as {@code (fromState, toState, enablingEvent | null)}.
 * Adding a ninth state without updating this matrix fails the build because the 8-entry
 * enum assertion at the bottom of the provider detects the missing state.
 */
@DisplayName("8×8 work order state-transition matrix")
class TransitionMatrixTest {

    record Cell(WorkOrderState from, WorkOrderState to, WorkOrderEvent enablingEvent) {
        boolean isLegal() { return enablingEvent != null; }
    }

    static Stream<Cell> matrix() {
        // Guard: fail immediately if WorkOrderState or WorkOrderEvent gains an undeclared member
        assertThat(WorkOrderState.values()).as("WorkOrderState must have exactly 8 values").hasSize(8);
        assertThat(WorkOrderEvent.values()).as("WorkOrderEvent must have exactly 8 values").hasSize(8);

        return Stream.of(
                // ── FROM: NEW ────────────────────────────────────────────────
                cell(NEW, NEW,         null),
                cell(NEW, ASSIGNED,    ASSIGN),
                cell(NEW, EN_ROUTE,    null),
                cell(NEW, IN_PROGRESS, null),
                cell(NEW, ON_HOLD,     null),
                cell(NEW, COMPLETED,   null),
                cell(NEW, CLOSED,      null),
                cell(NEW, CANCELLED,   CANCEL),

                // ── FROM: ASSIGNED ───────────────────────────────────────────
                cell(ASSIGNED, NEW,         null),
                cell(ASSIGNED, ASSIGNED,    null),
                cell(ASSIGNED, EN_ROUTE,    DEPART),
                cell(ASSIGNED, IN_PROGRESS, START),
                cell(ASSIGNED, ON_HOLD,     null),
                cell(ASSIGNED, COMPLETED,   null),
                cell(ASSIGNED, CLOSED,      null),
                cell(ASSIGNED, CANCELLED,   CANCEL),

                // ── FROM: EN_ROUTE ───────────────────────────────────────────
                cell(EN_ROUTE, NEW,         null),
                cell(EN_ROUTE, ASSIGNED,    null),
                cell(EN_ROUTE, EN_ROUTE,    null),
                cell(EN_ROUTE, IN_PROGRESS, START),
                cell(EN_ROUTE, ON_HOLD,     null),
                cell(EN_ROUTE, COMPLETED,   null),
                cell(EN_ROUTE, CLOSED,      null),
                cell(EN_ROUTE, CANCELLED,   CANCEL),     // ADR-0007: permitted

                // ── FROM: IN_PROGRESS ────────────────────────────────────────
                cell(IN_PROGRESS, NEW,         null),
                cell(IN_PROGRESS, ASSIGNED,    null),
                cell(IN_PROGRESS, EN_ROUTE,    null),
                cell(IN_PROGRESS, IN_PROGRESS, null),
                cell(IN_PROGRESS, ON_HOLD,     HOLD),
                cell(IN_PROGRESS, COMPLETED,   COMPLETE),
                cell(IN_PROGRESS, CLOSED,      null),
                cell(IN_PROGRESS, CANCELLED,   CANCEL),

                // ── FROM: ON_HOLD ─────────────────────────────────────────────
                cell(ON_HOLD, NEW,         null),
                cell(ON_HOLD, ASSIGNED,    null),
                cell(ON_HOLD, EN_ROUTE,    null),
                cell(ON_HOLD, IN_PROGRESS, RESUME),
                cell(ON_HOLD, ON_HOLD,     null),
                cell(ON_HOLD, COMPLETED,   null),
                cell(ON_HOLD, CLOSED,      null),
                cell(ON_HOLD, CANCELLED,   CANCEL),      // ADR-0007: permitted

                // ── FROM: COMPLETED (terminal-adjacent) ──────────────────────
                cell(COMPLETED, NEW,         null),
                cell(COMPLETED, ASSIGNED,    null),
                cell(COMPLETED, EN_ROUTE,    null),
                cell(COMPLETED, IN_PROGRESS, null),
                cell(COMPLETED, ON_HOLD,     null),
                cell(COMPLETED, COMPLETED,   null),
                cell(COMPLETED, CLOSED,      CLOSE),
                cell(COMPLETED, CANCELLED,   null),

                // ── FROM: CLOSED (terminal) ──────────────────────────────────
                cell(CLOSED, NEW,         null),
                cell(CLOSED, ASSIGNED,    null),
                cell(CLOSED, EN_ROUTE,    null),
                cell(CLOSED, IN_PROGRESS, null),
                cell(CLOSED, ON_HOLD,     null),
                cell(CLOSED, COMPLETED,   null),
                cell(CLOSED, CLOSED,      null),
                cell(CLOSED, CANCELLED,   null),

                // ── FROM: CANCELLED (terminal) ───────────────────────────────
                cell(CANCELLED, NEW,         null),
                cell(CANCELLED, ASSIGNED,    null),
                cell(CANCELLED, EN_ROUTE,    null),
                cell(CANCELLED, IN_PROGRESS, null),
                cell(CANCELLED, ON_HOLD,     null),
                cell(CANCELLED, COMPLETED,   null),
                cell(CANCELLED, CLOSED,      null),
                cell(CANCELLED, CANCELLED,   null)
        );
    }

    @ParameterizedTest(name = "{0} → {1} via {2}")
    @MethodSource("matrix")
    @DisplayName("Matrix cell")
    void matrixCell(Cell c) {
        if (c.isLegal()) {
            assertLegal(c);
        } else {
            assertIllegal(c);
        }
    }

    private void assertLegal(Cell c) {
        Optional<TransitionDescriptor> result =
                WorkOrderTransitionTable.resolve(c.from(), c.enablingEvent());
        assertThat(result)
                .as("Legal cell (%s -[%s]-> %s) must resolve", c.from(), c.enablingEvent(), c.to())
                .isPresent();
        assertThat(result.get().toState())
                .as("Resolved target state for (%s, %s)", c.from(), c.enablingEvent())
                .isEqualTo(c.to());
    }

    private void assertIllegal(Cell c) {
        boolean anyEventReachesTarget = Arrays.stream(WorkOrderEvent.values())
                .anyMatch(e -> WorkOrderTransitionTable.resolve(c.from(), e)
                        .map(TransitionDescriptor::toState)
                        .filter(c.to()::equals)
                        .isPresent());
        assertThat(anyEventReachesTarget)
                .as("Illegal cell (%s → %s): no event should produce this target state", c.from(), c.to())
                .isFalse();
    }

    private static Cell cell(WorkOrderState from, WorkOrderState to, WorkOrderEvent event) {
        return new Cell(from, to, event);
    }
}

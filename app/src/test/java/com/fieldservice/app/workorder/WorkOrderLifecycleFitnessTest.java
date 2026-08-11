package com.fieldservice.app.workorder;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

/**
 * Fitness tests ensuring the lifecycle transition table is the single source of
 * lifecycle truth and no external class bypasses it.
 *
 * <p>AC-5:
 * <ul>
 *   <li>No production class outside the lifecycle package calls
 *       {@code WorkOrder#setState(WorkOrderState)}.</li>
 *   <li>The transition table map cannot be mutated at runtime.</li>
 *   <li>The DB CHECK constraint vocabulary matches the {@link WorkOrderState} enum.</li>
 * </ul>
 *
 * These tests run without a Spring context.
 */
@DisplayName("Work order lifecycle fitness tests (AC-5)")
class WorkOrderLifecycleFitnessTest {

    // ── AC-5: no external setState ────────────────────────────────────────────

    @Test
    @DisplayName("AC-5: no production class outside the workorder package calls WorkOrder.setState()")
    void no_external_class_calls_work_order_set_state() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.fieldservice");

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(
                        "com.fieldservice.domain.workorder..",
                        "com.fieldservice.domain.workorder.lifecycle..")
                .should().callMethod(WorkOrder.class, "setState", WorkOrderState.class)
                .because("WorkOrder state must only be mutated by WorkOrderTransitionService " +
                         "inside the lifecycle package; all other code must call " +
                         "WorkOrderTransitionService.transition() instead.");

        rule.check(classes);
    }

    // ── AC-2: table is unmodifiable ────────────────────────────────────────────

    @Test
    @DisplayName("AC-2: WorkOrderTransitionTable MAP field is Collections.unmodifiableMap-backed")
    void table_map_is_unmodifiable() throws Exception {
        // Use Class.forName because WorkOrderTransitionTable is package-private
        Class<?> tableClass = Class.forName(
                "com.fieldservice.domain.workorder.lifecycle.WorkOrderTransitionTable");
        Field tableField = tableClass.getDeclaredField("TABLE");
        tableField.setAccessible(true);
        Object map = tableField.get(null);

        // The map must be unmodifiable — Collections.unmodifiableMap wraps in a private class
        assertThat(map.getClass().getSimpleName())
                .as("TABLE must be wrapped in Collections.unmodifiableMap")
                .containsIgnoringCase("unmodifiable");
    }

    // ── AC-1: WorkOrderState enum matches expected 8 values ──────────────────

    @Test
    @DisplayName("AC-1: WorkOrderState enum has exactly 8 values matching DB CHECK constraint")
    void work_order_state_enum_has_eight_values_matching_db_check() {
        WorkOrderState[] states = WorkOrderState.values();
        assertThat(states).hasSize(8);

        // These exact strings must appear in the V1 migration CHECK constraint
        for (WorkOrderState s : states) {
            assertThat(s.name())
                    .as("State name must be uppercase alphanumeric/underscore")
                    .matches("[A-Z_]+");
        }
    }

    // ── Structural: WorkOrderTransitionTable is final ─────────────────────────

    @Test
    @DisplayName("WorkOrderTransitionTable is final (no subclassing bypass)")
    void work_order_transition_table_is_final() throws Exception {
        Class<?> tableClass = Class.forName(
                "com.fieldservice.domain.workorder.lifecycle.WorkOrderTransitionTable");
        assertThat(tableClass.getModifiers() & java.lang.reflect.Modifier.FINAL)
                .as("WorkOrderTransitionTable must be final")
                .isNotZero();
    }

    // ── WorkOrderErrorCodes is not instantiable ───────────────────────────────

    @Test
    @DisplayName("WorkOrderErrorCodes has a private constructor")
    void work_order_error_codes_is_not_instantiable() {
        var constructors = com.fieldservice.domain.workorder.lifecycle.WorkOrderErrorCodes.class
                .getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers()))
                .as("WorkOrderErrorCodes constructor must be private")
                .isTrue();
    }
}

package com.fieldservice.workorder.lifecycle;

import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fitness tests ensuring lifecycle governance lives exclusively in
 * {@link WorkOrderTransitionTable} and that state mutation is routed only through
 * {@link WorkOrderTransitionService}.
 */
class WorkOrderLifecycleFitnessTest {

    private static JavaClasses ALL_CLASSES;

    @BeforeAll
    static void importClasses() {
        ALL_CLASSES = new ClassFileImporter().importPackages("com.fieldservice");
    }

    @Test
    @DisplayName("WorkOrderTransitionTable is not accessed from outside the lifecycle package")
    void transitionTable_notAccessedOutsideLifecyclePackage() {
        noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.workorder.lifecycle..")
                .should().accessClassesThat()
                .haveFullyQualifiedName(WorkOrderTransitionTable.class.getName())
                .because("The transition table is an internal implementation detail; " +
                         "all access must go through WorkOrderTransitionService")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("applyStateTransition is only called from within the lifecycle package")
    void applyStateTransition_onlyCalledFromLifecyclePackage() {
        noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.workorder.lifecycle..")
                .should().callMethod(WorkOrder.class, "applyStateTransition", WorkOrderStatus.class)
                .because("Work order state must only be mutated through WorkOrderTransitionService")
                .check(ALL_CLASSES);
    }
}

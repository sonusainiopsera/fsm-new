package com.fieldservice.app.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit fitness tests enforcing the AI gateway isolation boundary.
 *
 * <p>AC-11: No class in the dispatch, sla, workorder, or inventory modules may
 * depend on any class in the aigateway module. This ensures P0 workflows remain
 * deterministic and cannot be broken by AI provider degradation.
 */
@DisplayName("AI gateway isolation boundary (AC-11)")
class AiGatewayBoundaryTest {

    private static final JavaClasses ALL_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.fieldservice");

    @Test
    @DisplayName("AC-11: dispatch domain must not depend on aigateway")
    void dispatch_must_not_depend_on_aigateway() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.domain.dispatch..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway..")
                .because("Dispatch is a P0 workflow and must never fail because of the AI gateway.");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("AC-11: sla domain must not depend on aigateway")
    void sla_must_not_depend_on_aigateway() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.domain.sla..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway..")
                .because("SLA is a P0 domain and must never fail because of the AI gateway.");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("AC-11: workorder domain must not depend on aigateway")
    void workorder_must_not_depend_on_aigateway() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.domain.workorder..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway..")
                .because("Work-order lifecycle is a P0 workflow and must never block on the AI gateway.");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("AC-11: inventory domain must not depend on aigateway")
    void inventory_must_not_depend_on_aigateway() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.domain.inventory..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway..")
                .because("Inventory is a P0 domain and must never fail because of the AI gateway.");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("AC-1: aigateway internal package must not be accessible from outside the module")
    void aigateway_internal_must_not_be_imported_outside_aigateway() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.aigateway..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.internal..")
                .because("All HTTP clients, resilience config, and credentials are package-private " +
                         "implementation details invisible to callers.");
        rule.check(ALL_CLASSES);
    }
}

package com.fieldservice.app.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness test: business modules must not depend on the AI gateway internals.
 * The AI gateway is an infrastructure concern; business modules may only use the public
 * {@code AiGatewayPort} API surface, never internal implementation classes.
 */
class AiGatewayBoundaryTest {

    private static JavaClasses ALL_CLASSES;

    @BeforeAll
    static void importClasses() {
        ALL_CLASSES = new ClassFileImporter().importPackages("com.fieldservice");
    }

    @Test
    @DisplayName("dispatch module must not depend on ai-gateway internals")
    void dispatch_doesNotDependOnAiGatewayInternals() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.dispatch..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.internal..")
                .because("The ai-gateway internal package is an implementation detail; " +
                         "use AiGatewayPort from the api package only")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("sla module must not depend on ai-gateway internals")
    void sla_doesNotDependOnAiGatewayInternals() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.sla..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.internal..")
                .because("The ai-gateway internal package is an implementation detail; " +
                         "use AiGatewayPort from the api package only")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("workorder module must not depend on ai-gateway internals")
    void workorder_doesNotDependOnAiGatewayInternals() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.workorder..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.internal..")
                .because("The ai-gateway internal package is an implementation detail; " +
                         "use AiGatewayPort from the api package only")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("inventory module must not depend on ai-gateway internals")
    void inventory_doesNotDependOnAiGatewayInternals() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.inventory..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.internal..")
                .because("The ai-gateway internal package is an implementation detail; " +
                         "use AiGatewayPort from the api package only")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("no module may import from ai-gateway fake package in production code")
    void noProductionCode_importsFromFakePackage() {
        noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.aigateway.fake..")
                .and().resideOutsideOfPackage("..test..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.fake..")
                .because("The fake adapter is a test-only stub; production code must not import it")
                .check(ALL_CLASSES);
    }
}

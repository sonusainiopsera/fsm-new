package com.fieldservice.app.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the analytics module boundaries.
 *
 * <p>Rules enforced:
 * <ol>
 *   <li>Non-analytics code must not reach into {@code analytics.internal} — only the
 *       public {@code analytics} package surface is accessible.</li>
 *   <li>Analytics code must not reach into other modules' entity or repository
 *       packages — it may only depend on published event payload types and the
 *       platform primitives.</li>
 *   <li>Analytics code must not depend on the AI gateway internals.</li>
 * </ol>
 */
class ModuleBoundaryTest {

    private static JavaClasses ALL_CLASSES;

    @BeforeAll
    static void importClasses() {
        ALL_CLASSES = new ClassFileImporter().importPackages("com.fieldservice");
    }

    // ---------------------------------------------------------------
    // Rule 1: analytics.internal is not accessible from outside
    // ---------------------------------------------------------------

    @Test
    @DisplayName("No code outside analytics may depend on analytics.internal")
    void outsideCode_mustNotDependOn_analyticsInternals() {
        noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.analytics..")
                .and().resideOutsideOfPackage("..test..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.analytics.internal..")
                .because("analytics.internal is package-private; use KpiProjectionQuery from " +
                         "com.fieldservice.analytics only")
                .check(ALL_CLASSES);
    }

    // ---------------------------------------------------------------
    // Rule 2: analytics may not reach into other modules' domain/repo layers
    // ---------------------------------------------------------------

    @Test
    @DisplayName("analytics must not depend on workorder domain entities")
    void analytics_mustNotDependOn_workorderDomain() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.analytics..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.workorder.domain..")
                .because("analytics reads from events and its own projection table; " +
                         "it must not couple to domain entity objects from workorder module")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("analytics must not depend on workorder repository interfaces")
    void analytics_mustNotDependOn_workorderRepository() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.analytics..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.workorder.repository..")
                .because("analytics must aggregate from the read replica via its own " +
                         "KpiAggregator SPI, never by reaching into another module's repository")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("analytics must not depend on inventory domain entities")
    void analytics_mustNotDependOn_inventoryDomain() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.analytics..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.inventory.domain..")
                .because("analytics reads from events, not inventory entity objects directly")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("analytics must not depend on inventory repository interfaces")
    void analytics_mustNotDependOn_inventoryRepository() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.analytics..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.inventory.repository..")
                .check(ALL_CLASSES);
    }

    @Test
    @DisplayName("analytics must not depend on sla internal package")
    void analytics_mustNotDependOn_slaInternals() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.analytics..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.sla.internal..")
                .because("analytics consumes SLA events from the outbox, not from SLA internals directly")
                .check(ALL_CLASSES);
    }

    // ---------------------------------------------------------------
    // Rule 3: analytics must not depend on ai-gateway internals
    // ---------------------------------------------------------------

    @Test
    @DisplayName("analytics must not depend on ai-gateway internals")
    void analytics_mustNotDependOn_aiGatewayInternals() {
        noClasses()
                .that().resideInAPackage("com.fieldservice.analytics..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.aigateway.internal..")
                .because("analytics is a read-model substrate and has no AI concerns")
                .check(ALL_CLASSES);
    }

    // ---------------------------------------------------------------
    // Rule 4: notification.internal is not accessible from outside
    // ---------------------------------------------------------------

    @Test
    @DisplayName("No code outside notification may depend on notification.internal")
    void outsideCode_mustNotDependOn_notificationInternals() {
        noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.notification..")
                .and().resideOutsideOfPackage("..test..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.notification.internal..")
                .because("notification.internal is package-private; use NotificationPort from " +
                         "com.fieldservice.notification.api only")
                .check(ALL_CLASSES);
    }
}

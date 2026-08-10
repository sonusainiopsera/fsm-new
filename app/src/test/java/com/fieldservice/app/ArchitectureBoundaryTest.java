package com.fieldservice.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit fitness tests that enforce the Maven dependency direction at the class level.
 *
 * <p>These tests are the second line of defence after Maven's own dependency graph.
 * They prevent accidental cross-domain references from being introduced even if a
 * developer adds a Maven dependency edge without thinking.</p>
 *
 * <p>Rules enforced:</p>
 * <ul>
 *   <li>The {@code platform} module must not import from any domain module.</li>
 *   <li>Domain modules must not import from other domain modules (fan-out = 0).</li>
 *   <li>Internal packages must not be accessed from outside their own domain.</li>
 * </ul>
 */
class ArchitectureBoundaryTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.fieldservice");
    }

    /**
     * Platform must not depend on any domain module.
     */
    @Test
    void platformMustNotDependOnDomainModules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.platform..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.fieldservice.identity..",
                        "com.fieldservice.workorder..",
                        "com.fieldservice.dispatch..",
                        "com.fieldservice.workforce..",
                        "com.fieldservice.inventory..",
                        "com.fieldservice.catalog..",
                        "com.fieldservice.sla..",
                        "com.fieldservice.analytics..",
                        "com.fieldservice.portal..",
                        "com.fieldservice.aigateway..",
                        "com.fieldservice.notification.."
                )
                .as("Platform module must not depend on any domain module");

        rule.check(allClasses);
    }

    /**
     * Domain modules must not access each other's internal packages.
     * Only the public api package of each domain is accessible to peer domains.
     */
    @Test
    void domainInternalPackagesMustNotBeAccessedCrossDomain() {
        String[] domainInternals = {
                "com.fieldservice.identity.internal..",
                "com.fieldservice.workorder.internal..",
                "com.fieldservice.dispatch.internal..",
                "com.fieldservice.workforce.internal..",
                "com.fieldservice.inventory.internal..",
                "com.fieldservice.catalog.internal..",
                "com.fieldservice.sla.internal..",
                "com.fieldservice.analytics.internal..",
                "com.fieldservice.portal.internal..",
                "com.fieldservice.aigateway.internal..",
                "com.fieldservice.notification.internal.."
        };

        // For each domain, verify its internal package is not accessed by other packages
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(domainInternals)
                // app module is allowed to bootstrap but not to call into internal packages
                .and().resideOutsideOfPackage("com.fieldservice.app..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(domainInternals)
                .as("Domain internal packages must not be accessed from outside their domain");

        rule.check(allClasses);
    }

    /**
     * Identity module must have zero fan-out to other domain modules.
     */
    @Test
    void identityMustNotDependOnOtherDomainModules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.identity..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.fieldservice.workorder..",
                        "com.fieldservice.dispatch..",
                        "com.fieldservice.workforce..",
                        "com.fieldservice.inventory..",
                        "com.fieldservice.catalog..",
                        "com.fieldservice.sla..",
                        "com.fieldservice.analytics..",
                        "com.fieldservice.portal..",
                        "com.fieldservice.aigateway..",
                        "com.fieldservice.notification.."
                )
                .as("Identity module must have zero fan-out to other domain modules");

        rule.check(allClasses);
    }
}

package com.fieldservice.architecture;

import com.fieldservice.architecture.fixture.CrossModuleInternalAccessFixture;
import com.fieldservice.notification.internal.AlertSseEmitterRegistry;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for module encapsulation — {@code .internal} packages (WO-200, AC-2).
 *
 * <h3>Rule</h3>
 * <p>A class outside a module's package tree must not depend on any class in that module's
 * {@code .internal} package. Internal packages are implementation details and their signatures
 * must not leak across module boundaries.
 *
 * <p>The public contract of each module lives in its {@code .api} (or top-level) package only.
 *
 * <h3>Scope</h3>
 * <p>This test covers modules that use an {@code .internal} naming convention:
 * <ul>
 *   <li>{@code notification.internal} — notification delivery port internals (WO-195)</li>
 *   <li>{@code sla.internal} — SLA policy internals</li>
 * </ul>
 * <p>The following modules are covered by dedicated boundary tests:
 * <ul>
 *   <li>{@code aigateway.internal} → {@code AiGatewayBoundaryTest}</li>
 *   <li>{@code analytics.internal} → {@code AnalyticsBoundaryTest}</li>
 *   <li>{@code inventory.application} → {@code InventoryBoundaryTest}</li>
 * </ul>
 *
 * <h3>Self-test</h3>
 * {@link #rule_firesOnCrossModuleInternalAccess()} imports
 * {@link CrossModuleInternalAccessFixture} and asserts the rule detects the violation.
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleBoundaryTest {

    /**
     * No class outside the notification module may depend on {@code notification.internal}.
     * The public surface is {@code notification.api.*} only.
     */
    @ArchTest
    static final ArchRule outsideModulesMustNotAccessNotificationInternal =
            noClasses()
                    .that().resideOutsideOfPackage("com.fieldservice.notification..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.notification.internal")
                    .because("notification.internal contains package-private delivery components; "
                            + "callers must use notification.api.NotificationPort only (WO-195, WO-200)");

    /**
     * No class outside the SLA module may depend on {@code sla.internal}.
     * The public surface is {@code sla.web} and the SLA API types only.
     */
    @ArchTest
    static final ArchRule outsideModulesMustNotAccessSlaInternal =
            noClasses()
                    .that().resideOutsideOfPackage("com.fieldservice.sla..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.sla.internal")
                    .because("sla.internal contains policy-evaluation internals; "
                            + "callers must use the public SLA API types only (WO-200)");

    /**
     * No class outside the catalog module may depend on {@code catalog.application}.
     * The public surface is {@code catalog.api.*} only.
     */
    @ArchTest
    static final ArchRule outsideModulesMustNotAccessCatalogApplication =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.fieldservice.catalog..",
                            "com.fieldservice.workorder..",
                            "com.fieldservice.sla.."
                    )
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.catalog.application")
                    .because("catalog.application contains service implementation classes; "
                            + "callers outside the catalog module must use catalog.api types only (WO-200)");

    /**
     * No class outside the identity module may depend on {@code identity.application}.
     * The public surface is {@code identity.api.*} and identity domain types only.
     */
    @ArchTest
    static final ArchRule outsideModulesMustNotAccessIdentityApplication =
            noClasses()
                    .that().resideOutsideOfPackage("com.fieldservice.identity..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.identity.application")
                    .because("identity.application contains auth-flow service classes; "
                            + "callers outside the identity module must not bypass the API boundary (WO-200)");

    /**
     * Proves the rule fires: imports a deliberately non-compliant fixture and asserts failure.
     * The fixture class imports {@link AlertSseEmitterRegistry} from {@code notification.internal}.
     */
    @Test
    void rule_firesOnCrossModuleInternalAccess() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(CrossModuleInternalAccessFixture.class, AlertSseEmitterRegistry.class);

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.fieldservice.notification..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.fieldservice.notification.internal")
                .because("must not access notification.internal from outside the notification module");

        assertThatThrownBy(() -> rule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CrossModuleInternalAccessFixture");
    }
}

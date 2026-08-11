package com.fieldservice.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for WO-198 admin console boundaries.
 *
 * <p>Rules:
 * <ol>
 *   <li>The {@code workorder} module must not depend on {@code sla.internal} — the admin
 *       write path lives entirely inside {@code sla}; any workorder reaction to an SLA
 *       policy change must go through the outbox event, not a direct call.</li>
 *   <li>The {@code workorder} module must not depend on {@code sla.web} — controllers
 *       are HTTP-layer infrastructure; cross-module controller dependencies are forbidden.</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AdminSlaPolicyArchTest {

    /**
     * workorder must not have a compile-time or runtime dependency on sla.internal.
     *
     * <p>This ensures the admin write path (SlaPolicyService.updatePolicy) cannot be
     * called synchronously from workorder code, satisfying WO-198 AC-8.
     */
    @ArchTest
    static final ArchRule workorderMustNotDependOnSlaInternal =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.workorder..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.sla.internal..")
                    .because("workorder must react to SLA policy changes via the SlaPolicyChanged "
                            + "outbox event, not via a direct call into sla.internal");

    /**
     * workorder must not depend on sla.web (controller layer).
     */
    @ArchTest
    static final ArchRule workorderMustNotDependOnSlaWeb =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.workorder..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.sla.web..")
                    .because("cross-module controller dependencies are forbidden; "
                            + "workorder must use only the public sla API package");
}

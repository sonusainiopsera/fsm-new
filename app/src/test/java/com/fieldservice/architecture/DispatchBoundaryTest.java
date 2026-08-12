package com.fieldservice.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the dispatch module boundary (WO-133).
 *
 * <p>Rules:
 * <ul>
 *   <li>No class outside {@code dispatch} may access {@code dispatch.eligibility.*}
 *       — only the {@code dispatch.api} public surface is exported.</li>
 *   <li>No class outside {@code dispatch} may access {@code dispatch.internal.*}
 *       — Haversine and other internal utilities are not part of the public contract.</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class DispatchBoundaryTest {

    @ArchTest
    static final ArchRule outsideModulesMustNotAccessDispatchEligibilityInternals =
            noClasses()
                    .that().resideOutsideOfPackages("com.fieldservice.dispatch..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.dispatch.eligibility")
                    .because("dispatch.eligibility contains implementation classes that are not "
                            + "part of the public contract; callers must use dispatch.api only");

    @ArchTest
    static final ArchRule outsideModulesMustNotAccessDispatchInternalPackage =
            noClasses()
                    .that().resideOutsideOfPackages("com.fieldservice.dispatch..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.dispatch.internal")
                    .because("dispatch.internal contains utility classes (e.g. Haversine) "
                            + "that are not exposed as a public API; callers must use dispatch.api only");
}

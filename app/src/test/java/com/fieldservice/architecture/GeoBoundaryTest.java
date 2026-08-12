package com.fieldservice.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the geo module boundary (WO-135).
 *
 * <p>Rules:
 * <ul>
 *   <li>No class outside {@code geo} may access {@code geo.internal.*} — only
 *       the public port and result types in {@code geo.api} are exported.</li>
 *   <li>The {@code dispatch} module may depend on {@code geo.api} (the port) but
 *       must not import from {@code geo.internal} (the adapter implementation).</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class GeoBoundaryTest {

    @ArchTest
    static final ArchRule outsideModulesMustNotAccessGeoInternals =
            noClasses()
                    .that().resideOutsideOfPackages("com.fieldservice.geo..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.geo.internal")
                    .because("geo.internal contains adapter implementation classes; "
                            + "callers must use geo.api.TravelTimePort only");

    @ArchTest
    static final ArchRule dispatchMustDependOnGeoApiNotInternal =
            noClasses()
                    .that().resideInAPackage("com.fieldservice.dispatch..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.geo.internal")
                    .because("dispatch must depend only on geo.api (the port), "
                            + "not on adapter implementation details");
}

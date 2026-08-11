package com.fieldservice.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness test: P0 domain modules must never depend on the AI gateway.
 *
 * <p>The AI gateway is a non-critical, externally-dependent epic (EPIC-11). If any class
 * in the dispatch, SLA, work-order lifecycle, or inventory domains imports from the
 * {@code aigateway} package, a provider outage can cascade into P0 failures.
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AiGatewayBoundaryTest {

    @ArchTest
    static final ArchRule p0DomainsMustNotDependOnAiGateway =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.fieldservice.dispatch..",
                            "com.fieldservice.sla..",
                            "com.fieldservice.workorder..",
                            "com.fieldservice.inventory.."
                    )
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.fieldservice.aigateway..")
                    .because("dispatch, SLA, workorder and inventory are P0 paths; "
                            + "an AI provider outage must never cascade into them");

    @ArchTest
    static final ArchRule aiGatewayInternalMustNotBeAccessedFromOutside =
            noClasses()
                    .that().resideOutsideOfPackage("com.fieldservice.aigateway..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.aigateway.internal")
                    .because("internal AI gateway classes are package-private implementation details");
}

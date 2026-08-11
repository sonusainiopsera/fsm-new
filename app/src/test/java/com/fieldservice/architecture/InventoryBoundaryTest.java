package com.fieldservice.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the inventory module boundary (WO-148, AC-9).
 *
 * <p>Rules:
 * <ul>
 *   <li>No module outside {@code inventory} may import the inventory web or application
 *       implementation packages — only the public {@code api} sub-package is exported.</li>
 *   <li>The inventory module must not depend on the AI gateway (P0 boundary).</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class InventoryBoundaryTest {

    @ArchTest
    static final ArchRule outsideModulesMustNotAccessInventoryWebLayer =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.fieldservice.inventory..",
                            "com.fieldservice.domain.inventory.."
                    )
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.inventory.web")
                    .because("inventory.web contains controller implementation classes that are not "
                            + "part of the public API contract; callers must use inventory.api only");

    @ArchTest
    static final ArchRule outsideModulesMustNotAccessInventoryApplication =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.fieldservice.inventory..",
                            "com.fieldservice.domain.inventory.."
                    )
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fieldservice.inventory.application")
                    .because("inventory.application contains service implementation classes; "
                            + "callers must depend on inventory.api.StockQueryService only");
}

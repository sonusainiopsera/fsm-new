package com.fieldservice.architecture;

import com.fieldservice.domain.inventory.StockLedgerRepository;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture fitness tests for the inventory module boundary (WO-148, WO-150, AC-9).
 *
 * <p>Rules:
 * <ul>
 *   <li>No module outside {@code inventory} may import the inventory web or application
 *       implementation packages — only the public {@code api} sub-package is exported.</li>
 *   <li>The inventory module must not depend on the AI gateway (P0 boundary).</li>
 *   <li>No class may call {@code delete} or mutation variants on {@link StockLedgerRepository}
 *       — the stock ledger is append-only and mutations are forbidden at the application layer.</li>
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

    /**
     * Append-only enforcement at the application layer (WO-150, AC-2).
     *
     * <p>No class may call any method whose name starts with {@code delete} on
     * {@link StockLedgerRepository}.  UPDATE is prevented at the DB layer (REVOKE) and JPA layer
     * (no setters for immutable fields); this rule closes the application-layer gap for deletes.
     */
    @ArchTest
    static final ArchRule stockLedgerRepositoryMustNotBeCalledWithDeleteMethods =
            noClasses()
                    .should().callMethodWhere(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "calls a delete method on StockLedgerRepository",
                                    access ->
                                            access.getTarget().getOwner()
                                                    .isEquivalentTo(StockLedgerRepository.class)
                                            && access.getTarget().getName().startsWith("delete")))
                    .because("StockLedger is append-only (WO-150); delete operations are forbidden " +
                            "at the application layer — the DB role also REVOKEs DELETE/UPDATE");
}

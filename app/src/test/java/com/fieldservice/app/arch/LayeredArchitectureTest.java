package com.fieldservice.app.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for controller → service → repository layering.
 *
 * <p>Rules enforced:
 * <ol>
 *   <li>Controllers ({@code @RestController}) must not inject or access repository
 *       interfaces ({@code Repository}) directly; all data access must flow through
 *       a service layer.</li>
 * </ol>
 *
 * <p>Pre-existing violations in {@code workorder.web} and {@code inventory.web}
 * are recorded in
 * {@code src/test/resources/archunit/frozen-violations/README.txt} with dated
 * justifications and tracked for refactoring under BL-2026-001.
 *
 * <p>The rule is applied after excluding the known-legacy controllers via
 * {@code ignoreDependency}. Any NEW controller-to-repository dependency NOT
 * listed here will fail the build.
 *
 * <h3>Self-test layout</h3>
 * <ol>
 *   <li>{@link #controllers_must_not_access_repositories_directly} — positive rule on
 *       production code (new modules must be clean).</li>
 *   <li>{@link #rule_fires_on_violating_fixture} — proves the rule fires.</li>
 * </ol>
 */
class LayeredArchitectureTest {

    /** Production classes only — test classes are excluded to avoid false positives. */
    private static JavaClasses PROD_CLASSES;

    @BeforeAll
    static void importClasses() {
        PROD_CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.fieldservice");
    }

    // -----------------------------------------------------------------------
    // Known pre-existing legacy exceptions (BL-2026-001, frozen 2026-08-11).
    // Each ignoreDependency() call freezes exactly one controller-to-repository
    // pair; any new pair NOT listed here fails the build.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Controllers must not access Repository types directly")
    void controllers_must_not_access_repositories_directly() {
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().accessClassesThat()
                .areAssignableTo(Repository.class)
                .because("Controllers must delegate all data access to a service layer. "
                        + "See TESTING.md §ArchUnit-L1 for the rule and exception process.");

        // ---- Legacy exceptions (BL-2026-001, 2026-08-11) -------------------------
        // Each ignoreDependency(Controller, Repository) pair is a deliberate freeze;
        // any NEW controller-to-repository dependency not listed here breaks the build.

        rule = rule
                // workorder.web legacy batch
                .ignoreDependency(
                        com.fieldservice.workorder.web.WorkOrderController.class,
                        com.fieldservice.workorder.repository.WorkOrderRepository.class)
                .ignoreDependency(
                        com.fieldservice.workorder.web.WorkOrderController.class,
                        com.fieldservice.workorder.holds.WorkOrderHoldRepository.class)
                .ignoreDependency(
                        com.fieldservice.workorder.web.WorkOrderPartsController.class,
                        com.fieldservice.inventory.repository.StockLocationRepository.class)
                // inventory.web legacy batch
                .ignoreDependency(
                        com.fieldservice.inventory.web.InventoryPartsController.class,
                        com.fieldservice.inventory.repository.PartRepository.class)
                .ignoreDependency(
                        com.fieldservice.inventory.web.InventoryStockController.class,
                        com.fieldservice.inventory.repository.StockBalanceRepository.class)
                .ignoreDependency(
                        com.fieldservice.inventory.web.InventoryStockController.class,
                        com.fieldservice.inventory.repository.StockLocationRepository.class)
                .ignoreDependency(
                        com.fieldservice.inventory.web.InventoryMovementsController.class,
                        com.fieldservice.inventory.ledger.StockLedgerRepository.class)
                .ignoreDependency(
                        com.fieldservice.inventory.web.InventoryMovementsController.class,
                        com.fieldservice.inventory.repository.StockLocationRepository.class);

        rule.check(PROD_CLASSES);
    }

    /**
     * Proves the layering rule detects violations: applies the PURE (no ignores) rule
     * to the fixture package that contains a deliberately non-compliant controller.
     */
    @Test
    @DisplayName("Rule fires on deliberately non-compliant controller fixture")
    void rule_fires_on_violating_fixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.app.arch.fixture");

        ArchRule rule = noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().accessClassesThat()
                .areAssignableTo(Repository.class);

        assertThatThrownBy(() -> rule.check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ViolatingControllerFixture");
    }
}

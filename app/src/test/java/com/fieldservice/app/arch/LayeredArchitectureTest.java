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

    // ==========================================================================
    // WO-192 PII masking architecture rules
    // ==========================================================================

    /**
     * The platform.privacy package must NOT import from the privacy module.
     * This enforces the no-circular-dependency constraint: platform ← privacy,
     * never platform → privacy.
     */
    @Test
    @DisplayName("platform.privacy package must not import from the privacy module")
    void platform_privacy_must_not_depend_on_privacy_module() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.platform.privacy..")
                .should().accessClassesThat()
                .resideInAPackage("com.fieldservice.privacy..")
                .because("platform cannot depend on the privacy module — this would create a "
                        + "circular dependency. Use the FieldTierProvider bridge interface instead.");

        rule.check(PROD_CLASSES);
    }

    /**
     * Classes annotated {@code @DataClassification} (JPA entities) must not reside
     * in {@code *.web} packages.  Controllers must use DTOs — classified entities
     * must never be serialised as HTTP response bodies.
     */
    @Test
    @DisplayName("@DataClassification entities must not reside in web packages")
    void classified_entities_must_not_reside_in_web_packages() {
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(com.fieldservice.privacy.api.DataClassification.class)
                .should().resideInAPackage("..web..")
                .because("Classified entities must not live in web packages — use DTOs. "
                        + "See docs/privacy/masking-policy.md §DTO-Contract.");

        rule.check(PROD_CLASSES);
    }

    // ==========================================================================
    // WO-193 Envelope field encryption architecture rules
    // ==========================================================================

    /**
     * {@code SubjectKeySpec} must not reside in web or event packages.
     * Key material (wrapped in SubjectKeySpec) must never be returned in an API response
     * or included in a domain event payload.
     */
    @Test
    @DisplayName("SubjectKeySpec must not reside in web or event packages")
    void subject_key_spec_must_not_reside_in_web_or_event_packages() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("SubjectKeySpec")
                .should().resideInAPackage("..web..")
                .orShould().resideInAPackage("..event..")
                .because("SubjectKeySpec holds plaintext key material; it must never be "
                        + "serialised into an API response or event payload. "
                        + "See docs/security/cryptography-standards.md §key-hygiene.");

        rule.check(PROD_CLASSES);
    }

    /**
     * Classes outside the platform.crypto package must not call
     * {@code SubjectKeyManager.destroy()} directly.  Destruction must flow through
     * the privacy module's erasure service so audit trail and cache eviction are
     * guaranteed.
     */
    @Test
    @DisplayName("Only platform.crypto classes may call SubjectKeyManager directly from web layer")
    void web_layer_must_not_call_subject_key_manager_directly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..web..")
                .should().accessClassesThat()
                .implement(com.fieldservice.platform.crypto.SubjectKeyManager.class)
                .because("SubjectKeyManager must be accessed through the privacy module's "
                        + "erasure service, not directly from web controllers. "
                        + "See docs/security/cryptography-standards.md §key-destruction.");

        rule.check(PROD_CLASSES);
    }

    // ==========================================================================
    // WO-198 SLA policy / role matrix admin console architecture rules
    // ==========================================================================

    /**
     * The workorder module must not depend on SLA admin types.
     *
     * <p>Work-order processing may read published SLA thresholds (e.g. via an
     * application service interface), but it must never import internal SLA
     * admin DTOs, request/response records, or domain service beans.
     * This keeps the boundary clean: SLA configuration is an admin concern;
     * work-order fulfilment is an operational concern.
     */
    @Test
    @DisplayName("workorder module must not depend on sla admin types")
    void workorder_must_not_depend_on_sla_admin() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.fieldservice.workorder..")
                .should().accessClassesThat()
                .resideInAPackage("com.fieldservice.sla..")
                .because("Work-order classes must not import SLA admin types directly. "
                        + "Use a published application interface or event if cross-boundary "
                        + "communication is needed. See docs/arch/module-boundaries.md §sla-admin.");

        rule.check(PROD_CLASSES);
    }
}

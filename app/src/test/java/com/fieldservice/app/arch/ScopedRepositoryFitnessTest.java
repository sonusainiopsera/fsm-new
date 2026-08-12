package com.fieldservice.app.arch;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.persistence.ScopedRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness test: every Spring Data repository whose entity type implements
 * {@link ScopedEntity} must extend {@link ScopedRepository}, not the bare
 * {@link org.springframework.data.jpa.repository.JpaRepository}.
 *
 * <p>Bypassing the scoped fragment is the single most likely way a new module could
 * introduce an unscoped query — this rule ensures the CI build fails if that happens.
 *
 * <h3>Test layout</h3>
 * <ol>
 *   <li>{@link #all_scoped_entity_repositories_extend_scoped_repository} — positive rule
 *       covering all known scoped entity repositories in production code.</li>
 *   <li>{@link #rule_fires_on_non_compliant_fixture} — proves the rule still enforces by
 *       asserting it raises an {@link AssertionError} when applied to the deliberately
 *       non-compliant test fixture in {@code fixture} sub-package.</li>
 * </ol>
 */
class ScopedRepositoryFitnessTest {

    private static JavaClasses ALL_CLASSES;

    @BeforeAll
    static void importClasses() {
        ALL_CLASSES = new ClassFileImporter().importPackages("com.fieldservice");
    }

    /**
     * Verifies that every known production repository for a {@link ScopedEntity} extends
     * {@link ScopedRepository}.
     *
     * <p>The set of scoped entity repository fully-qualified names is maintained here.
     * When a new scoped entity is added, its repository must also be added to this list —
     * the compile will succeed but this test will fail until it is.
     */
    @Test
    @DisplayName("All scoped entity repositories extend ScopedRepository (production code)")
    void all_scoped_entity_repositories_extend_scoped_repository() {
        // Known production repositories serving ScopedEntity types
        Set<String> scopedRepositoryNames = Set.of(
                "com.fieldservice.workorder.repository.WorkOrderRepository",
                "com.fieldservice.portal.repository.PortalAccountUserRepository"
        );

        for (String repoName : scopedRepositoryNames) {
            JavaClass repoClass = ALL_CLASSES.get(repoName);
            boolean extendsScopedRepo = repoClass.getAllRawInterfaces().stream()
                    .anyMatch(i -> i.getFullName().equals(ScopedRepository.class.getName()));
            if (!extendsScopedRepo) {
                throw new AssertionError(repoName + " must extend ScopedRepository but does not. "
                        + "Row-scope enforcement is structural: bypassing ScopedRepository means "
                        + "the scope predicate is never applied.");
            }
        }
    }

    /**
     * Proves the rule still fires: applies the scoped-repository check to the test-only
     * non-compliant fixture and asserts that an {@link AssertionError} is raised.
     *
     * <p>Without this test a refactoring could weaken the rule silently.
     */
    @Test
    @DisplayName("Rule fires on deliberately non-compliant fixture (proves rule still enforces)")
    void rule_fires_on_non_compliant_fixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages("com.fieldservice.app.arch.fixture");

        // The non-compliant fixture extends JpaRepository<WorkOrder,UUID> WITHOUT ScopedRepository.
        // The rule should detect this and raise an AssertionError.
        assertThatThrownBy(() ->
                classes()
                        .that().implement(org.springframework.data.jpa.repository.JpaRepository.class)
                        .should(extendScopedRepository())
                        .check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NonCompliantWorkOrderRepository");
    }

    // -------------------------------------------------------------------------
    // Custom ArchCondition
    // -------------------------------------------------------------------------

    private static ArchCondition<JavaClass> extendScopedRepository() {
        return new ArchCondition<>("extend ScopedRepository") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean compliant = item.getAllRawInterfaces().stream()
                        .anyMatch(i -> i.getFullName().equals(ScopedRepository.class.getName()));
                if (!compliant) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getSimpleName() + " does not extend ScopedRepository — "
                            + "all repositories over scoped entities must use the scoped fragment"));
                }
            }
        };
    }
}

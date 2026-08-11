package com.fieldservice.architecture;

import com.fieldservice.architecture.fixture.ControllerAccessingRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for layered access discipline (WO-200, AC-1).
 *
 * <h3>Rule</h3>
 * <p>A class annotated with {@link RestController} must not directly depend on a class that
 * extends {@link JpaRepository}. Controllers must delegate to a service layer which owns the
 * query and transaction logic.
 *
 * <h3>Pre-existing exception</h3>
 * <p>{@code WorkOrderController} predates this rule: it directly injects
 * {@code WorkOrderRepository} and {@code WorkOrderHoldRepository} for a
 * {@code ScopedQueryExecutor} read pattern. That violation is frozen in
 * {@code src/test/resources/archunit/frozen/controllers_must_not_access_repositories_directly}.
 * See {@code TESTING.md §ArchUnit Rules} for the exception process.
 *
 * <h3>Self-test</h3>
 * {@link #rule_firesOnControllerAccessingRepository()} imports
 * {@link ControllerAccessingRepository} and asserts the rule detects the violation.
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class LayeredArchitectureTest {

    /**
     * Production rule: no {@code @RestController} class may directly depend on a
     * {@code JpaRepository} subtype.
     *
     * <p>The rule is wrapped in {@link FreezingArchRule} so the
     * {@code WorkOrderController} pre-existing violation is tolerated while any
     * NEW violation still breaks the build.
     */
    @ArchTest
    static final ArchRule controllersMustNotAccessRepositoriesDirectly =
            FreezingArchRule.freeze(
                    ArchRuleDefinition.noClasses()
                            .that().areAnnotatedWith(RestController.class)
                            .should().dependOnClassesThat()
                            .areAssignableTo(JpaRepository.class)
                            .because("controllers must delegate to a service layer; "
                                    + "direct repository access bypasses transaction management, "
                                    + "access-scope enforcement, and the service abstraction. "
                                    + "See TESTING.md §ArchUnit Rules for the exception process.")
            );

    /**
     * Proves the rule fires: imports a deliberately non-compliant fixture and asserts failure.
     */
    @Test
    void rule_firesOnControllerAccessingRepository() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(ControllerAccessingRepository.class);

        ArchRule unfrozenRule = ArchRuleDefinition.noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat()
                .areAssignableTo(JpaRepository.class)
                .because("controllers must not access repositories directly");

        assertThatThrownBy(() -> unfrozenRule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ControllerAccessingRepository");
    }
}

package com.fieldservice.architecture;

import com.fieldservice.architecture.fixture.UnscopedWorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness test for scoped-repository rule (WO-113 AC-11).
 *
 * <p>Rule: every interface in a domain entity package that extends {@link JpaRepository}
 * and is not an explicitly allow-listed non-scoped repository must also extend
 * {@link ScopedRepository}.
 *
 * <p>The non-scoped allow-list covers entities whose data is not row-scope sensitive:
 * {@code AppUser} (identity), {@code Part}/{@code StockLocation} (catalog/inventory infrastructure),
 * {@code IdempotencyKey}, {@code RoleAssignment}, and the {@code RefreshToken*} repositories
 * (identity plumbing, not domain aggregates).
 *
 * <p>A deliberately non-compliant fixture ({@link UnscopedWorkOrderRepository}) proves the
 * rule still fires when a new repository bypasses the fragment.
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ScopedRepositoryArchTest {

    // Non-scoped domain repository simple names that legitimately extend JpaRepository directly.
    private static final String NON_SCOPED_REPOSITORIES_PATTERN =
            "AppUserRepository|PartRepository|StockLocationRepository|"
                    + "IdempotencyKeyRepository|RoleAssignmentRepository|"
                    + "RefreshTokenRepository|RefreshTokenFamilyRepository";

    /**
     * Production rule: domain JPA repositories that are not in the non-scoped allow-list
     * must extend {@link ScopedRepository} to prevent unscoped queries over scoped entities.
     */
    @ArchTest
    static final ArchRule domainRepositoriesForScopedEntitiesMustExtendScopedRepository =
            classes()
                    .that().areInterfaces()
                    .and().haveSimpleNameEndingWith("Repository")
                    .and().resideInAPackage("com.fieldservice.domain..")
                    .and().areAssignableTo(JpaRepository.class)
                    .and().haveSimpleNameNotMatching(NON_SCOPED_REPOSITORIES_PATTERN)
                    .should().beAssignableTo(ScopedRepository.class)
                    .because("repositories for ScopedEntity types must extend ScopedRepository "
                            + "so unscoped reads are structurally impossible; "
                            + "add non-scoped entities to the allow-list in ScopedRepositoryArchTest");

    /**
     * Proves the rule still fires: import only the non-compliant fixture and assert the rule fails.
     */
    @Test
    void rule_firesOnNonCompliantFixture() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(UnscopedWorkOrderRepository.class);

        ArchRule simpleRule = classes()
                .that().areInterfaces()
                .and().areAssignableTo(JpaRepository.class)
                .should().beAssignableTo(ScopedRepository.class)
                .because("repositories must extend ScopedRepository");

        assertThatThrownBy(() -> simpleRule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnscopedWorkOrderRepository");
    }
}

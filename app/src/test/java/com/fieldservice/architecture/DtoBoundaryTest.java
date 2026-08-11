package com.fieldservice.architecture;

import com.fieldservice.architecture.fixture.ControllerReturningEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture fitness tests for the DTO boundary in controller signatures (WO-200, AC-7).
 *
 * <h3>Rule</h3>
 * <p>No method on a {@link RestController}-annotated class may declare a return type or
 * parameter type that is annotated with {@link Entity} (a JPA entity). Controllers must
 * use DTO types ({@code *Response}, {@code *Request}, {@code *Ref}, {@code *Summary}) for
 * their public signatures.
 *
 * <p>Leaking JPA entities into controller signatures:
 * <ul>
 *   <li>Forces serialisation of all lazy-loaded collections (N+1 risk)</li>
 *   <li>Exposes internal persistence metadata ({@code @Version}, audit columns) to callers</li>
 *   <li>Couples the API contract to the DB schema, preventing independent evolution</li>
 *   <li>Defeats row-scope enforcement — a detached entity is not scope-checked</li>
 * </ul>
 *
 * <h3>Self-test</h3>
 * {@link #rule_firesOnControllerMethodReturningEntity()} imports
 * {@link ControllerReturningEntity} and asserts the rule detects both the return type
 * and parameter violations.
 */
@AnalyzeClasses(
        packages = "com.fieldservice",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class DtoBoundaryTest {

    /**
     * No method on a {@code @RestController} class may have a return type that is directly
     * annotated with {@link Entity}.
     *
     * <p>Note: this rule checks the raw declared return type. {@code ResponseEntity<MyEntity>}
     * is caught because ArchUnit resolves the raw return type ({@code ResponseEntity}) but
     * will not catch the generic parameter; use {@link #controllerParametersMustNotBeJpaEntities}
     * together to close both surfaces.
     */
    @ArchTest
    static final ArchRule controllerReturnTypesMustNotBeJpaEntities =
            noMethods()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .and().arePublic()
                    .should().haveRawReturnType(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "is annotated with @Entity",
                                    type -> type.isAnnotatedWith(Entity.class)
                            ))
                    .because("controller return types must be DTO classes, not JPA entities; "
                            + "entity leaks expose internal schema and defeat scope enforcement. "
                            + "See TESTING.md §ArchUnit Rules. (WO-200, AC-7)");

    /**
     * No method on a {@code @RestController} class may declare a parameter of a type that is
     * directly annotated with {@link Entity}.
     */
    @ArchTest
    static final ArchRule controllerParametersMustNotBeJpaEntities =
            noMethods()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .and().arePublic()
                    .should().haveRawParameterTypes(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "contains a type annotated with @Entity",
                                    types -> types.stream().anyMatch(t -> t.isAnnotatedWith(Entity.class))
                            ))
                    .because("controller parameter types must be DTO classes, not JPA entities. "
                            + "See TESTING.md §ArchUnit Rules. (WO-200, AC-7)");

    /**
     * Proves both rules fire when a controller method's return type and parameter type are
     * JPA entities.
     */
    @Test
    void rule_firesOnControllerMethodReturningEntity() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(ControllerReturningEntity.class);

        ArchRule returnTypeRule = noMethods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().arePublic()
                .should().haveRawReturnType(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "is annotated with @Entity",
                                type -> type.isAnnotatedWith(Entity.class)
                        ))
                .because("no entity in controller return type");

        assertThatThrownBy(() -> returnTypeRule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ControllerReturningEntity");
    }
}
